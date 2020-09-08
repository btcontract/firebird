package org.aviran.cookiebar2;

import android.animation.Animator;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.btcontract.wallet.R;

import org.aviran.cookiebar2.CookieBarDismissListener.DismissType;

final class Cookie extends LinearLayout implements View.OnTouchListener {
    private Animation slideOutAnimation;
    private ViewGroup layoutCookie;
    private TextView messageTextView;
    private long duration = 2000;
    private int layoutGravity = Gravity.BOTTOM;
    private float initialDragX;
    private float dismissOffsetThreshold;
    private float viewWidth;
    private boolean swipedOut;
    private int animationInTop;
    private int animationInBottom;
    private int animationOutTop;
    private int animationOutBottom;
    private boolean isAutoDismissEnabled;
    private boolean isSwipeable;
    private CookieBarDismissListener dismissListener;
    private boolean timeOutDismiss;


    public Cookie(@NonNull final Context context) {
        this(context, null);
    }

    public Cookie(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Cookie(@NonNull final Context context, @Nullable final AttributeSet attrs,
                  final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public int getLayoutGravity() {
        return layoutGravity;
    }

    private void initViews(@LayoutRes int rootView, CookieBar.CustomViewInitializer viewInitializer) {
        if (rootView != 0) {
            inflate(getContext(), rootView, this);
            if (viewInitializer != null) {
                viewInitializer.initView(getChildAt(0));
            }
        } else {
            inflate(getContext(), R.layout.layout_cookie, this);
        }

        if (getChildAt(0).getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LayoutParams) (getChildAt(0).getLayoutParams());
            lp.gravity = Gravity.BOTTOM;
        }

        layoutCookie = findViewById(R.id.cookie);
        messageTextView = findViewById(R.id.tv_message);

        if(rootView == 0) {
            validateLayoutIntegrity();
            initDefaultStyle(getContext());
        }
        layoutCookie.setOnTouchListener(this);
    }

    private void validateLayoutIntegrity() {
        if (layoutCookie == null|| messageTextView == null) {
            throw new RuntimeException("Your custom cookie view is missing one of the default required views");
        }
    }

    private void initDefaultStyle(Context context) {
        int messageColor = Color.WHITE;

        messageTextView.setTextColor(messageColor);
    }

    public void setParams(final CookieBar.Params params) {
        initViews(params.customViewResource, params.viewInitializer);

        duration = params.duration;
        layoutGravity = params.cookiePosition;
        animationInTop = params.animationInTop;
        animationInBottom = params.animationInBottom;
        animationOutTop = params.animationOutTop;
        animationOutBottom = params.animationOutBottom;
        isSwipeable = params.enableSwipeToDismiss;
        isAutoDismissEnabled = params.enableAutoDismiss;
        dismissListener = params.dismissListener;

        if (messageTextView != null && !TextUtils.isEmpty(params.message)) {
            messageTextView.setVisibility(VISIBLE);
            messageTextView.setText(params.message);
            if (params.messageColor != 0) {
                messageTextView.setTextColor(ContextCompat.getColor(getContext(), params.messageColor));
            }
        }

        if (params.backgroundColor != 0) {
            layoutCookie
                    .setBackgroundColor(ContextCompat.getColor(getContext(), params.backgroundColor));
        }

        createInAnim();
        createOutAnim();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        viewWidth = getWidth();
        dismissOffsetThreshold = viewWidth / 3;

        if (layoutGravity == Gravity.TOP) {
            super.onLayout(changed, l, 0, r, layoutCookie.getMeasuredHeight());
        } else {
            super.onLayout(changed, l, t, r, b);
        }
    }

    private void createInAnim() {
        int animationResId = layoutGravity == Gravity.BOTTOM ? animationInBottom : animationInTop;
        Animation slideInAnimation = AnimationUtils.loadAnimation(getContext(), animationResId);
        slideInAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                // no implementation
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (!isAutoDismissEnabled) {
                    return;
                }

                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        timeOutDismiss = true;
                        dismiss();
                    }
                }, duration);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                // no implementation
            }
        });

        setAnimation(slideInAnimation);
    }

    private void createOutAnim() {
        int animationResId = layoutGravity == Gravity.BOTTOM ? animationOutBottom : animationOutTop;
        slideOutAnimation = AnimationUtils.loadAnimation(getContext(), animationResId);
    }

    public void dismiss() {
        try {
            dismiss(null);
        } catch (Exception ex) {
            // Do nothing
        }
    }

    public CookieBarDismissListener getDismissListenr() {
        return dismissListener;
    }

    public void dismiss(final CookieBarDismissListener listener) {
        getHandler().removeCallbacksAndMessages(null);
        if(listener != null) {
            dismissListener = listener;
        }

        if (swipedOut) {
            removeFromParent();
            cookieListenerDismiss(DismissType.USER_DISMISS);
            return;
        }

        slideOutAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(final Animation animation) {
                // no implementation
            }

            @Override
            public void onAnimationEnd(final Animation animation) {
                setVisibility(View.GONE);
                removeFromParent();
                cookieListenerDismiss(getDismissType());
            }

            @Override
            public void onAnimationRepeat(final Animation animation) {
                // no implementation
            }
        });

        startAnimation(slideOutAnimation);
    }

    private int getDismissType() {
        int dismissType = DismissType.PROGRAMMATIC_DISMISS;
        if(timeOutDismiss) {
            dismissType = DismissType.DURATION_COMPLETE;
        }
        return dismissType;
    }

    private void cookieListenerDismiss(@DismissType int dismissType) {
        if (dismissListener != null) {
            dismissListener.onDismiss(dismissType);
        }
    }

    private void removeFromParent() {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                ViewParent parent = getParent();
                if (parent != null) {
                    Cookie.this.clearAnimation();
                    ((ViewGroup) parent).removeView(Cookie.this);
                }
            }
        }, 200);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (!isSwipeable) {
            return true;
        }

        switch (motionEvent.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialDragX = motionEvent.getRawX();
                return true;

            case MotionEvent.ACTION_UP:
                if (!swipedOut) {
                    view.animate()
                            .x(0)
                            .alpha(1)
                            .setDuration(200)
                            .start();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (swipedOut) {
                    return true;
                }
                float offset = motionEvent.getRawX() - initialDragX;
                float alpha = 1 - Math.abs(offset / viewWidth);
                long duration = 0;

                if (Math.abs(offset) > dismissOffsetThreshold) {
                    offset = viewWidth * Math.signum(offset);
                    alpha = 0;
                    duration = 200;
                    swipedOut = true;
                }

                view.animate()
                        .setListener(swipedOut ? getDestroyListener() : null)
                        .x(offset)
                        .alpha(alpha)
                        .setDuration(duration)
                        .start();

                return true;

            default:
                return false;
        }
    }

    private Animator.AnimatorListener getDestroyListener() {
        return new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                // no implementation
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                dismiss();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // no implementation
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                // no implementation
            }
        };
    }
}
