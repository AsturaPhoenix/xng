package io.tqi.ekg.ifc.fx;

import javafx.geometry.Point2D;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;

public class Tappable {
    private static final int DRAG_START = 8;

    private Point2D preDrag;

    public void cancelTap() {
        preDrag = null;
    }

    public void onTouchPressed(final TouchEvent e) {
        if (e.getTouchCount() == 1) {
            preDrag = new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY());
        } else {
            cancelTap();
        }
    }

    public void onActualMousePressed(final MouseEvent e) {
        preDrag = new Point2D(e.getScreenX(), e.getScreenY());
    }

    /**
     * @return {@code true} if the event is no longer a tap, {@code false}
     *         otherwise.
     */
    public boolean onTouchMoved(final TouchEvent e) {
        if (preDrag == null || new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY())
                .distance(preDrag) >= DRAG_START) {
            preDrag = null;
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return {@code true} if the event is no longer a click, {@code false}
     *         otherwise.
     */
    public boolean onActualMouseDragged(final MouseEvent e) {
        if (preDrag == null || new Point2D(e.getScreenX(), e.getScreenY()).distance(preDrag) >= DRAG_START) {
            preDrag = null;
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return {@code true} if the release was a tap or click.
     */
    public boolean onReleased() {
        if (preDrag != null) {
            preDrag = null;
            return true;
        } else {
            return false;
        }
    }
}
