package com.sty.ne.opengl.screenrecord;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.SurfaceHolder;

public class MyGLSurfaceView extends GLSurfaceView {
    private MyGLRender mRender;
    public MyGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initGL();
    }

    public void initGL() {
        setEGLContextClientVersion(2); //设置egl版本
        mRender = new MyGLRender(this);
        setRenderer(mRender); //设置自定义渲染器
        setRenderMode(RENDERMODE_WHEN_DIRTY); //设置按需渲染模式
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        super.surfaceDestroyed(holder);
        mRender.surfaceDestroyed();
    }
}
