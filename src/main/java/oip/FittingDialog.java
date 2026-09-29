/*-
 * #%L
 * Per-object, cross-channel intensity profiles and texture measurements for ImageJ and Fiji
 * %%
 * Copyright (C) 2026 Jamie Malcolm
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the UK Dementia Research Institute at Imperial College London nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package oip;

import ij.IJ;
import ij.gui.GUI;
import ij.gui.GenericDialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.ScrollPane;
import java.awt.Window;

/**
 * A {@link GenericDialog} that always fits on the screen. When the packed dialog is taller or
 * wider than the usable screen area (a laptop at 125% display scaling offers about 1536 x 824
 * pixels, and ImageJ's GUI scale enlarges dialogs further), its fields move into a scroll pane
 * and the OK/Cancel row stays fixed underneath. Without this the window is clamped to the
 * screen and its top rows and OK button are cut off.
 */
class FittingDialog extends GenericDialog {

    /** Pixels scrolled per mouse-wheel notch or arrow click. */
    static final int SCROLL_STEP = 24;

    private boolean fitted;

    FittingDialog(String title) {
        super(title);
    }

    @Override
    public void pack() {
        super.pack();
        if (!fitted) {
            Window reference = IJ.getInstance() != null ? IJ.getInstance() : this;
            fitted = fitTo(GUI.getMaxWindowBounds(reference));
        }
    }

    /**
     * Moves every component except the last (GenericDialog adds its button row last, just
     * before packing) into a scroll pane when the packed dialog does not fit {@code screen}.
     *
     * @return true when the dialog was rearranged
     */
    boolean fitTo(Rectangle screen) {
        Dimension packed = getSize();
        if (packed.width <= screen.width && packed.height <= screen.height) return false;
        if (!(getLayout() instanceof GridBagLayout)) return false;
        Component[] parts = getComponents();
        if (parts.length < 2) return false;
        GridBagLayout grid = (GridBagLayout) getLayout();
        GridBagConstraints[] constraints = new GridBagConstraints[parts.length];
        for (int i = 0; i < parts.length; i++) constraints[i] = grid.getConstraints(parts[i]);

        Panel content = new Panel(new GridBagLayout());
        for (int i = 0; i < parts.length - 1; i++) content.add(parts[i], constraints[i]);
        Component buttons = parts[parts.length - 1];
        remove(buttons);
        setLayout(new BorderLayout());
        ScrollPane scroll = new ScrollPane(ScrollPane.SCROLLBARS_AS_NEEDED);
        scroll.add(content);
        scroll.getVAdjustable().setUnitIncrement(SCROLL_STEP);
        scroll.getHAdjustable().setUnitIncrement(SCROLL_STEP);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        Insets insets = getInsets();
        Dimension inner = content.getPreferredSize();
        int width = inner.width + scroll.getVScrollbarWidth() + insets.left + insets.right + 8;
        int height = inner.height + buttons.getPreferredSize().height
                + scroll.getHScrollbarHeight() + insets.top + insets.bottom + 8;
        setSize(Math.min(width, screen.width), Math.min(height, screen.height));
        validate();
        return true;
    }
}
