
package com.ybs.passwordstrengthmeter;

import android.graphics.Color;
import com.btcontract.wallet.R;

public enum PasswordStrength {

  WEAK(R.string.password_strength_weak, Color.RED),
  MEDIUM(R.string.password_strength_medium, Color.argb(255, 220, 185, 0)),
  GOOD(R.string.password_strength_good, Color.GREEN);

  int resId;
  int color;

  PasswordStrength(int resId, int color) {
    this.resId = resId;
    this.color = color;
  }

  public CharSequence getText(android.content.Context ctx) {
    return ctx.getText(resId);
  }

  public int getColor() {
    return color;
  }

  public static PasswordStrength calculateStrength(String password) {
    boolean sawUpper = false;
    boolean sawLower = false;
    boolean sawDigit = false;
    int length = password.length();

    for (int i = 0; i < length; i++) {
      char c = password.charAt(i);
      if (Character.isDigit(c)) {
        sawDigit = true;
      } else {
        if (Character.isUpperCase(c)) {
          sawUpper = true;
        } else {
          sawLower = true;
        }
      }
    }

    if (length == 8 && sawUpper && sawLower && sawDigit)  {
      return MEDIUM;
    } else if (length > 8 && sawUpper && sawLower && sawDigit) {
      return GOOD;
    } else if (length == 10 && sawUpper && sawLower) { // Mixed case, without digits
      return MEDIUM;
    } else if (length > 10 && sawUpper && sawLower) { // Mixed case, with possible digits
      return GOOD;
    } else if (length == 12 && (sawUpper || sawLower)) { // Single case, with possible digits, not purely digital
      return MEDIUM;
    } else if (length > 12 && (sawUpper || sawLower)) { // Single case, with possible digits, not purely digital
      return GOOD;
    } else {
      return WEAK;
    }
  }
}
