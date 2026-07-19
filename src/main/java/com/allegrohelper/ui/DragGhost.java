package com.allegrohelper.ui;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JWindow;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Window;
import java.awt.RenderingHints;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceMotionListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Source-side visual feedback for gallery drags: a "copy" cursor plus a
 * floating stack of the dragged thumbnails that follows the pointer.
 *
 * <p>This exists because X11's drag protocol (XDND — what Java uses even
 * under Wayland, via XWayland) has no drag-image support:
 * {@link DragSource#isDragImageSupported()} is false and
 * {@link TransferHandler#setDragImage} is silently ignored, so without it a
 * drag shows nothing but the plain arrow. The workaround is a small
 * always-on-top window moved from {@link DragSourceMotionListener}, which
 * keeps firing over external apps because the drag source holds the pointer
 * grab. The window trails the hotspot by a fixed offset so the pointer is
 * never over it — otherwise the XDND target search would find our own
 * window instead of the real drop target.
 */
final class DragGhost extends DragSourceAdapter implements DragSourceMotionListener {

    /** Gap between the pointer hotspot and the ghost window (px). */
    private static final int POINTER_OFFSET = 18;
    /** Longest side of a thumbnail in the ghost (px). */
    private static final int GHOST_THUMB = 96;
    /** X/Y shift between stacked thumbnails (px). */
    private static final int GHOST_STEP = 12;
    /** At most this many thumbnails are stacked; a badge shows the true count. */
    private static final int GHOST_MAX_STACK = 3;

    private final JList<Object> list;
    private JWindow window;

    DragGhost(JList<Object> list) {
        this.list = list;
        // The default DragSource is shared, so events from every drag in the
        // app arrive here; mine() filters down to this gallery's list.
        DragSource ds = DragSource.getDefaultDragSource();
        ds.addDragSourceListener(this);
        ds.addDragSourceMotionListener(this);
    }

    private boolean mine(DragSourceEvent e) {
        return e.getDragSourceContext().getComponent() == list;
    }

    @Override
    public void dragMouseMoved(DragSourceDragEvent e) {
        if (!mine(e)) {
            return;
        }
        if (window == null) {
            window = createWindow();
            // A custom cursor disables the automatic drop-feedback cursor,
            // which never signals anything useful across the XWayland
            // bridge anyway; a constant "copy" cursor beats a plain arrow.
            e.getDragSourceContext().setCursor(DragSource.DefaultCopyDrop);
        }
        window.setLocation(e.getX() + POINTER_OFFSET, e.getY() + POINTER_OFFSET);
        if (!window.isVisible()) {
            window.setVisible(true);
        }
    }

    @Override
    public void dragDropEnd(DragSourceDropEvent e) {
        if (mine(e) && window != null) {
            window.dispose();
            window = null;
        }
    }

    private JWindow createWindow() {
        JWindow w = new JWindow();
        w.setType(Window.Type.POPUP); // override-redirect: no WM decoration or focus
        w.setAlwaysOnTop(true);
        w.setFocusableWindowState(false);
        BufferedImage image = ghostImage();
        GraphicsDevice device = w.getGraphicsConfiguration().getDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            w.setBackground(new Color(0, 0, 0, 0));
        }
        JLabel label = new JLabel(new ImageIcon(image));
        ((JComponent) w.getContentPane()).setOpaque(false);
        w.add(label);
        w.pack();
        return w;
    }

    /** Renders the selected thumbnails as a slightly offset stack with a count badge. */
    private BufferedImage ghostImage() {
        List<Image> thumbs = new ArrayList<>();
        for (Object value : list.getSelectedValuesList()) {
            if (value instanceof ImageIcon icon && thumbs.size() < GHOST_MAX_STACK) {
                thumbs.add(icon.getImage());
            }
        }
        int count = (int) list.getSelectedValuesList().stream().filter(v -> v instanceof ImageIcon).count();
        int size = GHOST_THUMB + GHOST_STEP * (Math.max(thumbs.size(), 1) - 1);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = thumbs.size() - 1; i >= 0; i--) { // bottom of the stack first
            Image t = thumbs.get(i);
            double scale = (double) GHOST_THUMB / Math.max(t.getWidth(null), t.getHeight(null));
            int tw = (int) Math.round(t.getWidth(null) * scale);
            int th = (int) Math.round(t.getHeight(null) * scale);
            int x = i * GHOST_STEP;
            int y = i * GHOST_STEP;
            g.drawImage(t, x, y, tw, th, null);
            g.setColor(Color.WHITE);
            g.drawRect(x, y, tw - 1, th - 1);
        }
        if (count > 1) {
            String text = String.valueOf(count);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
            int d = 22; // badge diameter
            int bx = size - d - 2;
            g.setColor(UiStyle.TAB_ACCENT);
            g.fillOval(bx, 2, d, d);
            g.setColor(Color.WHITE);
            var fm = g.getFontMetrics();
            g.drawString(text, bx + (d - fm.stringWidth(text)) / 2, 2 + (d + fm.getAscent() - fm.getDescent()) / 2);
        }
        g.dispose();
        return image;
    }
}
