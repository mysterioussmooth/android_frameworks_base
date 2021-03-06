/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.animation.AccelerateInterpolator;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.List;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.policy.ExtensibleKeyButtonView;
import com.android.systemui.statusbar.policy.DeadZone;

public class NavigationBarView extends LinearLayout {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED= true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because it introduces unsightly delay when videos goes to full screen

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;
    boolean mScreenOn;

    boolean mHidden, mLowProfile, mShowMenu;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;

    private Drawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    
    private DelegateViewHelper mDelegateHelper;
    private DeadZone mDeadZone;

    private SettingsObserver mSettingsObserver;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    // Navbar Custom target defines.
    final static String ACTION_HOME = "**home**";
    final static String ACTION_BACK = "**back**";
    final static String ACTION_SEARCH = "**search**";
    final static String ACTION_MENU = "**menu**";
    final static String ACTION_POWER = "**power**";
    final static String ACTION_NOTIFICATIONS = "**notifications**";
    final static String ACTION_RECENTS = "**recents**";
    final static String ACTION_SCREENSHOT = "**screenshot**";
    final static String ACTION_IME = "**ime**";
    final static String ACTION_LAST_APP = "**lastapp**";
    final static String ACTION_KILL = "**kill**";
    final static String ACTION_NULL = "**null**";

    int mNumberOfButtons = 3;

    int mTablet_UI = 0;

    private float mAlpha;
    private int mAlphaMode;
    private int mNavBarColor;
    private int mNavBarButtonColor;
    private int mNavBarButtonColorMode;
    private boolean mIsHome = true;

    public String[] mClickActions = new String[7];
    public String[] mLongpressActions = new String[7];
    public String[] mPortraitIcons = new String[7];

    public final static int sStockButtonsQty = 3;
    public final static String[] StockClickActions = {
        "**back**", "**home**", "**recents**", "**null**", "**null**", "**null**", "**null**"
    };

    public final static String[] StockLongpress = {
        "**null**", "**null**", "**null**", "**null**", "**null**", "**null**", "**null**"
    };
    FrameLayout rot0;
    FrameLayout rot90;

    // Definitions for navbar menu button customization
    public final static int SHOW_LEFT_MENU = 1;
    public final static int SHOW_RIGHT_MENU = 0;
    public final static int SHOW_BOTH_MENU = 2;
    public final static int SHOW_DONT = 4;

    public final static int VISIBILITY_SYSTEM = 0;
    public final static int VISIBILITY_SYSTEM_AND_INVIZ = 3;
    public final static int VISIBILITY_NEVER = 1;
    public final static int VISIBILITY_ALWAYS = 2;

    public static final int KEY_MENU_RIGHT = 2;
    public static final int KEY_MENU_LEFT = 5;

    private int mCurrentVisibility;
    private int mCurrentSetting;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public void setDelegateView(View view) {
        mDelegateHelper.setDelegateView(view);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mDelegateHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDelegateHelper.onInterceptTouchEvent(event);
    }

    private H mHandler = new H();

    public View getLeftMenuButton() {
        return mCurrentView.findViewById(R.id.menu_left);
    }

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getRightMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getBackButton() {
        return mCurrentView.findViewById(R.id.back);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    // for when home is disabled, but search isn't
    public View getSearchLight() {
        return mCurrentView.findViewById(R.id.search_light);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
        mDelegateHelper = new DelegateViewHelper(this);

        mBackIcon = res.getDrawable(R.drawable.ic_sysbar_back);
        mBackLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_land);
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
    }

    private void makeBar() {

        ((LinearLayout) rot0.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot0.findViewById(R.id.lights_out)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.lights_out)).removeAllViews();

        for (int i = 0; i <= 1; i++) {
            boolean landscape = (i == 1);

            LinearLayout navButtonLayout = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.nav_buttons) : rot0
                    .findViewById(R.id.nav_buttons));

            LinearLayout lightsOut = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.lights_out) : rot0
                    .findViewById(R.id.lights_out));

            // add left menu
            if (mCurrentSetting != SHOW_DONT) {
                View leftmenuKey = generateKey(landscape, KEY_MENU_LEFT);
                addButton(navButtonLayout, leftmenuKey, landscape);
                addLightsOutButton(lightsOut, leftmenuKey, landscape, true);
            }

            int mLongpressEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SYSTEMUI_NAVBAR_LONG_ENABLE, 0);

            for (int j = 0; j < mNumberOfButtons; j++) {

                if (mLongpressEnabled == 0) {
                            mLongpressActions[j] = "**null**";
                }

                ExtensibleKeyButtonView v = generateKey(landscape, mClickActions[j],
                        mLongpressActions[j],
                        mPortraitIcons[j]);
                v.setTag((landscape ? "key_land_" : "key_") + j);

                addButton(navButtonLayout, v, landscape);
                addLightsOutButton(lightsOut, v, landscape, false);

                if (v.getId() == R.id.back) {
                    mBackIcon = mBackLandIcon = v.getDrawable();
                }

                if (mNumberOfButtons == 3 && j != (mNumberOfButtons - 1)) {
                    // add separator view here
                    View separator = new View(mContext);
                    separator.setLayoutParams(getSeparatorLayoutParams(landscape));
                    addButton(navButtonLayout, separator, landscape);
                    addLightsOutButton(lightsOut, separator, landscape, true);
                }

            }
            if (mCurrentSetting != SHOW_DONT) {
                View rightMenuKey = generateKey(landscape, KEY_MENU_RIGHT);
                addButton(navButtonLayout, rightMenuKey, landscape);
                addLightsOutButton(lightsOut, rightMenuKey, landscape, true);
            }
        }
        setMenuVisibility(false, true);

        Drawable bg = mContext.getResources().getDrawable(R.drawable.nav_bar_bg);
        if(bg instanceof ColorDrawable) {
            BackgroundAlphaColorDrawable bacd = new BackgroundAlphaColorDrawable(
                    mNavBarColor != -2 ? mNavBarColor : ((ColorDrawable) bg).getColor());
            setBackground(bacd);
        }
        updateKeyguardAlpha();
    }

    private void addLightsOutButton(LinearLayout root, View v, boolean landscape, boolean empty) {

        ImageView addMe = new ImageView(mContext);
        addMe.setLayoutParams(v.getLayoutParams());
        addMe.setImageResource(empty ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        addMe.setScaleType(ImageView.ScaleType.CENTER);
        addMe.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    private void addButton(ViewGroup root, View addMe, boolean landscape) {
        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    private View generateKey(boolean landscape, int keyId) {
        KeyButtonView v = null;
        Resources r = getResources();

        int btnWidth = 80;

        switch (keyId) {

            case KEY_MENU_RIGHT:
                v = new KeyButtonView(mContext, null);
                v.setLayoutParams(getLayoutParams(landscape, 40));

                v.setId(R.id.menu);
                v.setCode(KeyEvent.KEYCODE_MENU);
                v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                        : R.drawable.ic_sysbar_menu);
                v.setVisibility(View.INVISIBLE);
                v.setContentDescription(r.getString(R.string.accessibility_menu));
                v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                        : R.drawable.ic_sysbar_highlight);
                if (mNavBarButtonColor == 0x00000000)
                    v.setColorFilter(null);
                else
                    v.setColorFilter(mNavBarButtonColor, Mode.SRC_ATOP);
                return v;

            case KEY_MENU_LEFT:
                v = new KeyButtonView(mContext, null);
                v.setLayoutParams(getLayoutParams(landscape, 40));

                v.setId(R.id.menu_left);
                v.setCode(KeyEvent.KEYCODE_MENU);
                v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                        : R.drawable.ic_sysbar_menu);
                v.setVisibility(View.INVISIBLE);
                v.setContentDescription(r.getString(R.string.accessibility_menu));
                v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                        : R.drawable.ic_sysbar_highlight);
                if (mNavBarButtonColor == 0x00000000)
                    v.setColorFilter(null);
                else
                    v.setColorFilter(mNavBarButtonColor, Mode.SRC_ATOP);
                return v;

        }

        return null;
    }

    private ExtensibleKeyButtonView generateKey(boolean landscape, String clickAction,
            String longpress,
            String iconUri) {

        final int iconSize = 80;
        ExtensibleKeyButtonView v = new ExtensibleKeyButtonView(mContext, null, clickAction,
                longpress);
        v.setLayoutParams(getLayoutParams(landscape, iconSize));

        boolean drawableSet = false;

        if (iconUri != null) {
            if (iconUri.length() > 0) {
                // custom icon from the URI here
                File f = new File(Uri.parse(iconUri).getPath());
                if (f.exists()) {
                    v.setImageDrawable(new BitmapDrawable(getResources(), f.getAbsolutePath()));
                    if (mNavBarButtonColor == 0x00000000 || mNavBarButtonColorMode == 1)
                        v.setColorFilter(null);
                    else
                        v.setColorFilter(mNavBarButtonColor, Mode.MULTIPLY);
                    drawableSet = true;
                }
            }
            if (!drawableSet && clickAction != null && !clickAction.startsWith("**")) {
                // here it's not a system action (**action**), so it must be an
                // app intent
                try {
                    Drawable d = mContext.getPackageManager().getActivityIcon(
                            Intent.parseUri(clickAction, 0));
                    final int[] appIconPadding = getAppIconPadding();
                    if (landscape)
                        v.setPaddingRelative(appIconPadding[1], appIconPadding[0],
                                appIconPadding[3], appIconPadding[2]);
                    else
                        v.setPaddingRelative(appIconPadding[0], appIconPadding[1],
                                appIconPadding[2], appIconPadding[3]);
                    v.setImageDrawable(d);
                    if (mNavBarButtonColor == 0x00000000  || mNavBarButtonColorMode != 0)
                        v.setColorFilter(null);
                    else
                        v.setColorFilter(mNavBarButtonColor, Mode.MULTIPLY);
                    drawableSet = true;
                } catch (NameNotFoundException e) {
                    e.printStackTrace();
                    drawableSet = false;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    drawableSet = false;
                }
            }
        }

        if (!drawableSet) {
            v.setImageDrawable(getNavbarIconImage(landscape, clickAction));
            if (mNavBarButtonColor == 0x00000000)
                v.setColorFilter(null);
            else
                v.setColorFilter(mNavBarButtonColor, Mode.SRC_ATOP);
        }

        v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                : R.drawable.ic_sysbar_highlight);
        return v;
    }

    private int[] getAppIconPadding() {
        int[] padding = new int[4];
        // left
        padding[0] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // top
        padding[1] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources()
                .getDisplayMetrics());
        // right
        padding[2] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // bottom
        padding[3] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                getResources()
                        .getDisplayMetrics());
        return padding;
    }

    private LayoutParams getLayoutParams(boolean landscape, float dp) {
        float px = dp * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px, 1f) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT, 1f);
    }

    private LayoutParams getSeparatorLayoutParams(boolean landscape) {
        float px = 25 * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT);
    }

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        setDisabledFlags(mDisabledFlags, true);
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;

        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;
        // We can't gaurantee users will set these buttons as targets
        if (getBackButton() != null) {
            getBackButton().setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP)) ? 0.5f : 1.0f);
            ((ImageView)getBackButton()).setImageDrawable(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT))
                    ? (mVertical ? mBackAltLandIcon : mBackAltIcon)
                    : (mVertical ? mBackLandIcon : mBackIcon));
        }
        if (getHomeButton()!=null) {
            getHomeButton().setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP)) ? 0.5f : 1.0f);
        }
        if (getRecentsButton()!=null) {
            getRecentsButton().setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP)) ? 0.5f : 1.0f);
        }

        setDisabledFlags(mDisabledFlags, true);
        updateKeyguardAlpha();
    }

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    private boolean isKeyguardEnabled() {
        return ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0) && !((mDisabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);
    }

    private void updateKeyguardAlpha() {
        if((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0
                || (isKeyguardEnabled() && mAlphaMode == 0)
                || (!isKeyguardEnabled() && mIsHome == false && mAlphaMode != 2)) {
            setBackgroundAlpha(1);
        } else {
            setBackgroundAlpha(mAlpha);
        }
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);
        final boolean keygaurdProbablyEnabled = isKeyguardEnabled();

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }

        if (!mScreenOn && mCurrentView != null) {
            ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
            LayoutTransition lt = navButtons == null ? null : navButtons.getLayoutTransition();
            if (lt != null) {
                lt.disableTransitionType(
                        LayoutTransition.CHANGE_APPEARING | LayoutTransition.CHANGE_DISAPPEARING |
                        LayoutTransition.APPEARING | LayoutTransition.DISAPPEARING);
            }
        }

        for (int j = 0; j < mNumberOfButtons; j++) {
            View v = (View) findViewWithTag((mVertical ? "key_land_" : "key_") + j);
            if (v != null) {
                int vid = v.getId();
                if (vid == R.id.back) {
                    v.setVisibility(disableBack ? View.INVISIBLE : View.VISIBLE);
                } else if (vid == R.id.recent_apps) {
                    v.setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
                } else { // treat all other buttons as same rule as home
                    v.setVisibility(disableHome ? View.INVISIBLE : View.VISIBLE);
                }

            }
        }

        updateKeyguardAlpha();
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show)
            return;

        if (mCurrentSetting == SHOW_DONT) {
            return;
        }

        mShowMenu = show;
        boolean localShow = show;

        ImageView leftButton = (ImageView) getLeftMenuButton();
        ImageView rightButton = (ImageView) getRightMenuButton();

        switch (mCurrentVisibility) {
            case VISIBILITY_SYSTEM:
                leftButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                rightButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                break;
            case VISIBILITY_ALWAYS:
                leftButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                rightButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                localShow = true;
                break;
            case VISIBILITY_NEVER:
                leftButton
                        .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                rightButton
                        .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                localShow = true;
                break;
            case VISIBILITY_SYSTEM_AND_INVIZ:
                if (localShow) {
                    leftButton
                            .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                    : R.drawable.ic_sysbar_menu);
                    ((ImageView) getRightMenuButton())
                            .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                    : R.drawable.ic_sysbar_menu);
                } else {
                    localShow = true;
                    leftButton
                            .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                    rightButton
                            .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                }
                break;
        }

        // do this after just in case show was changed
        switch (mCurrentSetting) {
            case SHOW_BOTH_MENU:
                leftButton.setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                rightButton.setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                break;
            case SHOW_LEFT_MENU:
                leftButton.setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                rightButton.setVisibility(View.INVISIBLE);
                break;
            default:
            case SHOW_RIGHT_MENU:
                leftButton.setVisibility(View.INVISIBLE);
                rightButton.setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                break;
        }
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        if (!force && lightsOut == mLowProfile) return;

        mLowProfile = lightsOut;

        if (DEBUG) Slog.d(TAG, "setting lights " + (lightsOut?"out":"on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                .alpha(lightsOut ? 0f : 1f)
                .setDuration(lightsOut ? 750 : 250)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lightsOut ? 1f : 0f)
                .setDuration(lightsOut ? 750 : 250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    @Override
    public void onFinishInflate() {
        rot0 = (FrameLayout) findViewById(R.id.rot0);
        rot90 = (FrameLayout) findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);
        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                 ? findViewById(R.id.rot90)
                 : findViewById(R.id.rot270);

         for (View v : mRotatedViews) {
             // this helps avoid drawing artifacts with glowing navigation keys
             ViewGroup group = (ViewGroup) v.findViewById(R.id.nav_buttons);
             group.setMotionEventSplittingEnabled(false);
         }
         mCurrentView = mRotatedViews[Surface.ROTATION_0];

         // this takes care of activity broadcasts for alpha mode
         BroadcastObserver broadcastObserver = new BroadcastObserver(new Handler());
         broadcastObserver.observe();

         // this takes care of making the buttons
         mSettingsObserver = new SettingsObserver(new Handler());
         updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSettingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        super.onDetachedFromWindow();
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }

        if (mTablet_UI != 0 || Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.NAVIGATION_BAR_CAN_MOVE, 1) != 1) {
            mCurrentView = mRotatedViews[Surface.ROTATION_0];
        } else {
            mCurrentView = mRotatedViews[rot];
        }
        mCurrentView.setVisibility(View.VISIBLE);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        setNavigationIconHints(mNavigationIconHints, true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mDelegateHelper.setInitialTouchRegion(getHomeButton(), getBackButton(), getRecentsButton());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Slog.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)", 
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Slog.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */

    /*
     * ]0 < alpha < 1[
     */
    private void setBackgroundAlpha(float alpha) {
        Drawable bg = getBackground();
        if(bg == null) return;

        if(bg instanceof BackgroundAlphaColorDrawable) {
            if(mNavBarColor != -2) {
                ((BackgroundAlphaColorDrawable) bg).setBgColor(mNavBarColor);
            }
        }
        int a = Math.round(alpha * 255);
        bg.setAlpha(a);
    }

    class BroadcastObserver extends ContentObserver {
        BroadcastObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.IS_HOME), false,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mIsHome = Settings.System.getInt(getContext().getContentResolver(),
                   Settings.System.IS_HOME, 1) == 1;
            updateKeyguardAlpha();
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_LOCATION), false,
                    this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_VISIBILITY), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTONS_QTY), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_TINT), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTON_TINT), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_ALPHA), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_NAV_BAR_ALPHA_MODE), false,
                    this);

            for (int j = 0; j < 7; j++) { // watch all 5 settings for changes.
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System
                                .getUriFor(Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.SYSTEMUI_NAVBAR_LONG_ENABLE),
                        false,
                        this);
            }
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    protected void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mCurrentSetting = Settings.System.getInt(resolver,
                Settings.System.MENU_LOCATION, SHOW_RIGHT_MENU);

        mCurrentVisibility = Settings.System.getInt(resolver,
                Settings.System.MENU_VISIBILITY, VISIBILITY_SYSTEM);

        mTablet_UI = Settings.System.getInt(resolver,
                Settings.System.TABLET_UI, 0);

        mNavBarButtonColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_BUTTON_TINT, 0x00000000);

        mNavBarButtonColorMode = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE, 0);

        mNavBarColor = Settings.System.getInt(resolver,
                Settings.System.NAVIGATION_BAR_TINT, -2);

        mAlpha = 1 - Settings.System.getFloat(resolver,
                Settings.System.NAVIGATION_BAR_ALPHA, 0.0f);

        mAlphaMode = Settings.System.getInt(resolver,
                Settings.System.STATUS_NAV_BAR_ALPHA_MODE, 1);

        mNumberOfButtons = Settings.System.getInt(resolver,
                Settings.System.NAVIGATION_BAR_BUTTONS_QTY, 0);
        if (mNumberOfButtons == 0) {
            mNumberOfButtons = sStockButtonsQty;
            Settings.System.putInt(resolver,
                    Settings.System.NAVIGATION_BAR_BUTTONS_QTY, sStockButtonsQty);
        }

        for (int j = 0; j < 7; j++) {
            mClickActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]);
            if (mClickActions[j] == null) {
                mClickActions[j] = StockClickActions[j];
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j], mClickActions[j]);
            }

            mLongpressActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]);

            if (mLongpressActions[j] == null) {
                mLongpressActions[j] = StockLongpress[j];
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j], mLongpressActions[j]);
            }

            mPortraitIcons[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]);
            if (mPortraitIcons[j] == null) {
                mPortraitIcons[j] = "";
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j], "");
            }
        }
        makeBar();

    }

    private Drawable getNavbarIconImage(boolean landscape, String uri) {

        if (uri == null)
            return getResources().getDrawable(R.drawable.ic_sysbar_null);

        if (uri.startsWith("**")) {
            if (uri.equals(ACTION_HOME)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_home);
            } else if (uri.equals(ACTION_BACK)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_back);
            } else if (uri.equals(ACTION_RECENTS)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_recent);
            } else if (uri.equals(ACTION_SCREENSHOT)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_screenshot);
            } else if (uri.equals(ACTION_SEARCH)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_search);
            } else if (uri.equals(ACTION_MENU)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_menu_big);
            } else if (uri.equals(ACTION_IME)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_ime_switcher);
            } else if (uri.equals(ACTION_LAST_APP)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_lastapp);
            } else if (uri.equals(ACTION_KILL)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_killtask);
            } else if (uri.equals(ACTION_POWER)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_power);
            } else if (uri.equals(ACTION_NOTIFICATIONS)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_notifications);
            }
        }

        return getResources().getDrawable(R.drawable.ic_sysbar_null);
    }
        

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

}
