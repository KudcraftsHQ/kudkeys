/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

public final class PopupKeysDetector extends KeyDetector {
    private final int mSlideAllowanceSquare;
    private final int mSlideAllowanceSquareTop;
    private final int mSlideAllowanceSquareBottom;

    public PopupKeysDetector(float slideAllowance) {
        super();
        mSlideAllowanceSquare = (int)(slideAllowance * slideAllowance);
        // Top slide allowance is slightly longer (sqrt(2) times) than other edges.
        mSlideAllowanceSquareTop = mSlideAllowanceSquare * 2;
        // Kud Keys: the finger starts just inside the bottom row (see PopupKeysKeyboardView.anchorSlideStart) and
        // popups on the bottom keyboard row have little screen below them, so below the panel the allowance is half.
        mSlideAllowanceSquareBottom = mSlideAllowanceSquare / 4;
    }

    @Override
    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return true;
    }

    @Override
    public Key detectHitKey(final int x, final int y) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        Key nearestKey = null;
        final int nearestDistStart;
        if (y < 0) nearestDistStart = mSlideAllowanceSquareTop;
        else if (touchY > keyboard.mOccupiedHeight) nearestDistStart = mSlideAllowanceSquareBottom;
        else nearestDistStart = mSlideAllowanceSquare;
        int nearestDist = nearestDistStart;
        for (final Key key : keyboard.getSortedKeys()) {
            final int dist = key.squaredDistanceToEdge(touchX, touchY);
            if (dist < nearestDist) {
                nearestKey = key;
                nearestDist = dist;
            }
        }
        return nearestKey;
    }
}
