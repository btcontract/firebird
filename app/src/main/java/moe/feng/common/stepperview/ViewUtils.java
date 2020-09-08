package moe.feng.common.stepperview;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;

class ViewUtils {

	/**
	 * Get color attribute from current theme
	 *
	 * @param context Themed context
	 * @param attr The resource id of color attribute
	 * @return Result
	 */
	@ColorInt
	static int getColorFromAttr(Context context, @AttrRes int attr) {
		TypedArray array = context.getTheme().obtainStyledAttributes(new int[]{attr});
		int color = array.getColor(0, Color.TRANSPARENT);
		array.recycle();
		return color;
	}

	static ObjectAnimator createArgbAnimator(View view, String propertyName, int startColor, int endColor) {
        return ObjectAnimator.ofArgb(view, propertyName, startColor, endColor);
	}

}
