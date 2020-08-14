package me.relex.circleindicator;

import android.view.Gravity;
import android.widget.LinearLayout;
import com.btcontract.wallet.R;

public class Config {

    int width = -1;
    int height = -1;
    int margin = -1;
    int animatorResId = R.animator.scale_with_alpha;
    int animatorReverseResId = 0;
    int backgroundResId = R.drawable.pager_dot_radius;
    int unselectedBackgroundId;
    int orientation = LinearLayout.HORIZONTAL;
    int gravity = Gravity.CENTER;

    Config() {
    }

    public static class Builder {

        private final Config mConfig;

        public Builder() {
            mConfig = new Config();
        }

        public Builder width(int width) {
            mConfig.width = width;
            return this;
        }

        public Builder height(int height) {
            mConfig.height = height;
            return this;
        }

        public Builder margin(int margin) {
            mConfig.margin = margin;
            return this;
        }

        public Builder animator(int animatorResId) {
            mConfig.animatorResId = animatorResId;
            return this;
        }

        public Builder animatorReverse(int animatorReverseResId) {
            mConfig.animatorReverseResId = animatorReverseResId;
            return this;
        }

        public Builder drawable(int backgroundResId) {
            mConfig.backgroundResId = backgroundResId;
            return this;
        }

        public Builder drawableUnselected(int unselectedBackgroundId) {
            mConfig.unselectedBackgroundId = unselectedBackgroundId;
            return this;
        }

        public Builder orientation(int orientation) {
            mConfig.orientation = orientation;
            return this;
        }

        public Builder gravity(int gravity) {
            mConfig.gravity = gravity;
            return this;
        }

        public Config build() {
            return mConfig;
        }
    }
}
