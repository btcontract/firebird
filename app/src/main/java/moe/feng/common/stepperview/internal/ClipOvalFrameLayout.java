package moe.feng.common.stepperview.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * @hide
 */
public class ClipOvalFrameLayout extends FrameLayout {

	private Path path = new Path();

	public ClipOvalFrameLayout(Context context) {
		super(context);
		init();
	}

	public ClipOvalFrameLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public ClipOvalFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {

	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		super.dispatchDraw(canvas);
	}

}
