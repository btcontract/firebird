
package com.ybs.passwordstrengthmeter;

import android.graphics.Color;
import com.btcontract.wallet.R;

public enum PasswordStrength {

  WEAK(R.string.password_strength_weak, Color.RED),
  MEDIUM(R.string.password_strength_medium, Color.argb(255, 220, 185, 0)),
  GOOD(R.string.password_strength_good, Color.GREEN);

  //--------REQUIREMENTS--------
  static int REQUIRED_LENGTH = 9;
  static int MAXIMUM_LENGTH = 16;
  static boolean REQUIRE_DIGITS = true;
  static boolean REQUIRE_LOWER_CASE = true;
  static boolean REQUIRE_UPPER_CASE = true;

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
    int currentScore = 0;
    boolean sawUpper = false;
    boolean sawLower = false;
    boolean sawDigit = false;

    for (int i = 0; i < password.length(); i++) {
      char c = password.charAt(i);
      if (!sawDigit && Character.isDigit(c)) {
        currentScore += 1;
        sawDigit = true;
      } else {
        if (!sawUpper || !sawLower) {
          if (Character.isUpperCase(c))
            sawUpper = true;
          else
            sawLower = true;
          if (sawUpper && sawLower)
            currentScore += 1;
        }
      }
    }

    if (password.length() > REQUIRED_LENGTH) {
      if ((REQUIRE_UPPER_CASE && !sawUpper) || (REQUIRE_LOWER_CASE && !sawLower) || (REQUIRE_DIGITS && !sawDigit)) {
        currentScore = 1;
      } else {
        currentScore = 2;
        if (password.length() > MAXIMUM_LENGTH) {
          currentScore = 3;
        }
      }
    } else {
      currentScore = 0;
    }

    switch (currentScore) {
      case 0:
        return WEAK;
      case 1:
        return MEDIUM;
      case 2:
      case 3:
        return GOOD;
      default:
    }

    return GOOD;
  }

}
