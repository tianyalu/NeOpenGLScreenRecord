# `OpenGL` 视频录制处理--变速录制

[TOC]

本文通过`OpenGL`，利用`MediaCodec`和`EGL`实现了视频的变速录制。本文代码基于 [NeOpenGL](https://github.com/tianyalu/NeOpenGL) 。

## 一、实现效果

实现效果如下图所示：

![image](https://github.com/tianyalu/NeOpenGLScreenRecord/raw/master/show/show.gif)  

## 二、 基础概念

### 2.1 `MediaCodec`

参考：[视频录制MediaCodec资料](https://www.jianshu.com/p/fa10f33a1ec5)  

`MediaCodec` 是`Android 4.1.2(API 16)` 提供的一套编解码`API`。它的使用非常简单，它存在一个输入缓冲区与一个输出缓冲区，在编码时我们将数据塞入输入缓冲区，然后冲输出缓冲区取出编码完成后的数据就可以了。  

![image](https://github.com/tianyalu/NeOpenGLScreenRecord/raw/master/show/media_codec.png)  

除了直接操作输入缓冲区之外，还有另一种方式来告知`MediaCodec`需要编码的数据：  

```java
public native final Surface createInputSurface();
```

使用此接口创建一个`Surface`，然后我们在这个`Surface`中“作画”，`MediaCodec`就能够自动地编码`Surface`中的“画作”，我们只需要从缓冲区取出编码完成之后的数据即可。

此前我们使用`OpenGL`进行绘画显示在屏幕上，然而想要复制屏幕图像到`CPU`内存中却不是一件非常轻松的事情。所以我们可以直接将`OpenGL`显示到屏幕中的图像同时绘制到`MediaCodec#createInputSurface`当中去。

#### 2.1.1 `PBO`

`PBO(Pixel Buffer Object)`：像素缓存对象，通过直接的内存访问（`Direct Memory Access, DMA`）高速地复制屏幕图像像素数据到`CPU`内存，但是这里我们直接使用`createInputSurface`更简单。

#### 2.1.2 录制

录制时我们在另一个线程中进行（**录制线程**），所以录制的`EGL`环境和显示的`EGL`环境（`GLSurfaceView`，**显示线程**）是两个独立的工作环境，但它们有能够共享上下文资源：**显示线程**中使用的`texture`等，需要能够在**录制线程**中操作（通过**录制线程**中使用`OpenGL`绘制到`MediaCodec`的`Surface`）。

在这个线程中，我们需要实现：

> 1. 配置录制使用的`EGL`环境（参考`GLSurfaceView`是怎么配置的）  
> 2. 将显示的图像绘制到`MediaCodec`的`Surface`中
> 3. 编码（H.264）与复用（封装MP4）的工作

### 2.2 `EGL`

参考：[EGL接口解析与理解](https://www.jianshu.com/p/299d23340528)    

通俗上讲，`OpenGL`是一个操作`GPU`的`API`，它通过驱动向`GPU`发送相关指令，控制图形渲染管线状态机的运行状态。但`OpenGL`需要本地视窗系统进行交互，这就需要一个中间控制层，最好与平台无关---`EGL`应运而生，它被独立地设计出来，作为`OpenGL ES`和本地窗口的桥梁

#### 2.2.1 `EGL`介绍

`EGL`是`OpenGL ES`(嵌入式)和底层`Native`平台视窗系统之间的接口。`EGL API`是独立于`OpenGL ES`各版本标准的独立的`API`，其主要作用是为`OpenGL`指令创建`Context`、绘制目标`Surface`、配置`Framebuffer`属性、`Swap`提交绘制结果等。此外，`EGL`为`GPU`厂商和`OS`窗口系统之间提供了一个标准配置的接口。

一般来说，`OpenGL ES`图形管线的状态被存储于`EGL`管理的一个`Context`中，而`Frame Buffers`和其它绘制`Surfaces`通过`EGL API` 进行创建、管理和销毁。`EGL`同时也控制和提供了对设备显示和可能的设备渲染配置的访问。

`EGL`是`C`的，在`Android`系统`Java`层封装了相关`API`。

#### 2.2.2 `EGL Displays`

`EGLDisplay`是一个关联系统物理屏幕的通用数据类型，表示显示设备句柄，也可以认为是一个前端显示窗。为了使用系统的显示设备，`EGL`提供了`EGLDisplay`数据类型，以及一组操作设备显示的`API`。

下面的函数原型用于获取`Native Display`：

```java
EGLDisplay eglGetDisplay (NativeDisplayType display);
```

> 其中`display`参数是`native`系统的窗口显示`ID`值。如果你只是想得到一个系统默认的`Display`，你可以使用`EGL_DEFAULT_DISPLAY`参数。如果系统中没有一个可用的`native display ID`与给定的`display`参数匹配，函数返回`EGL_NO_DESPLAY`，而没有任何`Error`状态被设置。由于设置无效的`display`值不会有任何错误状态，在继续操作前请检测返回值。  

#### 2.2.3 `EGL`操作步骤

* 初始化`EGL`

  > 1. 获取`Display`
  > 2. 初始化`EGL`
  > 3. 选择`Config`

* 构造`Surface`

  > 1. 创建`Context`
  > 2. `EGL`变量之间的绑定
  > 3. 绘制

* `EGL Configurations`属性配置

详情参考：`record/MyEGL.java`  

#### 2.2.4 `eglSwapBuffers`接口实现说明

一般嵌入式平台：  

![image](https://github.com/tianyalu/NeOpenGLScreenRecord/raw/master/show/egl_swap_buffers.png)    

利用双缓冲进行`Swap`的时候，`Display`和`Surface`进行实际意义上的地址交换，来实现`eglSwapBuffers`的标准，如上图的右侧所示。

上图左侧表示的是单缓冲`Framebuffer`的形式，`Surface`永远都在后端，显示的永远是`Display`，此种方式在`GPU`出现后已不再使用。  


## 三、实现思路及步骤

### 3.1 实现思路

本文实现视频录制的思路是：开始预览后，将摄像头数据渲染到`FBO`，拿到此时的`textureId`，然后把该`FBO`通过`ScreenFilter`渲染到屏幕（可见的`Surface`）上，实现预览效果；当开启录制后，同时将该`FBO`渲染到由`MediaCodec`创建的`Surface`（不可见），并且通过该`Surface`创建的`EGLSurface`上，然后通过`MediaCodec`从其输出缓冲区拿到已经编码好的数据，通过`MediaMuxer`封装器输出到指定的文件中，实现视频录制。其中变速录制只需要在封装器输出到文件时进行加速或减速处理即可。

### 3.2 实现步骤

MyGLSurfaceView --> initGL() --> new MyGLRender()  

MyGLRender --> onSurfaceCreated() --> new SurfaceTexture() | new CameraFilter() | new ScreenFilter() | new MyMediaRecodrder()

​                      --> onSurfaceChanged() --> CameraHelper.startPreview(mSurfaceTexture)

​                      --> onDrawFrame() --> ScreenFilter.onDrawFrame(textureId) | MyMediaRecorder.encodeFrame(textureId) --> MyEGL.draw() | getEncodedData() --> 输出录制内容

MyGLSurfaceView.startRecording() --> MyGLRender.startRecording(speed) --> MyMediaRecorder.start(speed) --> new MyEGL() | MediaCodec.start()















