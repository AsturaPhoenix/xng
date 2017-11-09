package io.tqi.ekg.ifc.fx;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.common.base.Strings;

import io.reactivex.Observable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Synapse.Activation;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.transform.Affine;
import javafx.scene.transform.NonInvertibleTransformException;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import lombok.Getter;

public class GraphPanel extends StackPane {
    private static final double ROTATION_FACTOR = 2;
    private static final double GRAPH_SCALE = 100;
    private static final double TRANSLATION_FACTOR = GRAPH_SCALE / 55;
    private static final double ZOOM_FACTOR = GRAPH_SCALE / 11;

    private static final double NODE_MAJ_RAD = .45;
    private static final double NODE_MIN_RAD = .15;

    public interface Selectable {
        void select();

        void deselect();
    }

    private class Connection extends Group {
        final NodeGeom end;
        final Group lineBillboard = new Group();
        final Line line = new Line();
        final Rotate rotate1 = new Rotate(0, Rotate.Z_AXIS), rotate2 = new Rotate(0, Rotate.Y_AXIS);

        Connection(final NodeGeom end) {
            this.end = end;
            end.incoming.add(this);
            line.setStrokeWidth(.01 * GRAPH_SCALE);
            lineBillboard.getTransforms().add(rotate1);
            lineBillboard.getTransforms().add(rotate2);
            lineBillboard.getChildren().add(line);
            getChildren().add(lineBillboard);
        }

        void setColor(final Color color1, Color color2) {
            line.setStroke(new LinearGradient(0, 0, .05, 0, true, CycleMethod.REPEAT, new Stop(0, color1),
                    new Stop(1, color2)));
        }

        void update() {
            final Point3D delta = sceneToLocal(end.localToScene(Point3D.ZERO));
            final double xyMag = Math.sqrt(delta.getX() * delta.getX() + delta.getY() * delta.getY());
            line.setEndX(delta.magnitude());
            if (xyMag > 0)
                rotate1.setAngle(Math.toDegrees(Math.atan2(delta.getY() / xyMag, delta.getX() / xyMag)));
            rotate2.setAngle(Math.toDegrees(Math.asin(-delta.getZ() / line.getEndX())));
        }
    }

    private class PropConnection extends Connection implements Selectable {
        final Connection spur;
        final Label caption = new Label();
        final Scale flip = new Scale();
        final DropShadow selGlow = new DropShadow(GRAPH_SCALE / 10, Color.DEEPSKYBLUE);

        PropConnection(final NodeGeom property, final NodeGeom value) {
            super(value);
            setColor(Color.TRANSPARENT, Color.BLACK);
            spur = new Connection(property);
            spur.setColor(Color.BLUE.deriveColor(0, 1, 1, .1), Color.TRANSPARENT);
            getChildren().add(spur);

            caption.setFont(new Font(.08 * GRAPH_SCALE));
            caption.setAlignment(Pos.CENTER);
            caption.getTransforms().add(flip);
            lineBillboard.getChildren().add(caption);
        }

        @Override
        void update() {
            super.update();
            final String text = spur.end.text.getText();
            if (Strings.isNullOrEmpty(text)) {
                caption.setVisible(false);
            } else {
                caption.setText(text);
                if (rotate1.getAngle() > -90 && rotate1.getAngle() <= 90) {
                    flip.setX(1);
                    flip.setY(1);
                    flip.setZ(1);
                } else {
                    flip.setPivotX(line.getEndX() / 2);
                    flip.setX(-1);
                    flip.setY(-1);
                    flip.setZ(-1);
                }
                caption.setPrefWidth(line.getEndX());
                caption.setVisible(true);
            }
            final Point3D midpt = sceneToLocal(end.localToScene(Point3D.ZERO)).multiply(.5);
            spur.setTranslateX(midpt.getX());
            spur.setTranslateY(midpt.getY());
            spur.setTranslateZ(midpt.getZ());
            spur.update();
        }

        @Override
        public void select() {
            setEffect(selGlow);
        }

        @Override
        public void deselect() {
            setEffect(null);
        }
    }

    private class NodeGeom extends Group implements Selectable {
        final WeakReference<io.tqi.ekg.Node> node;
        final Ellipse body;
        final Label text;

        final Group geom = new Group();
        final Group connections = new Group();
        final Set<Connection> incoming = new HashSet<>();

        final InnerShadow selEffect = new InnerShadow(GRAPH_SCALE / 4, Color.DEEPSKYBLUE);

        final Tappable tappable = new Tappable();

        int dragPtId;
        Point3D dragPt;

        boolean selected;

        Point3D calcTp(final TouchEvent e) {
            final TouchPoint pt = e.getTouchPoint();
            return calcTp((Node) e.getSource(), pt.getX(), pt.getY(), pt.getZ());
        }

        Point3D calcTp(final MouseEvent e) {
            return calcTp((Node) e.getSource(), e.getX(), e.getY(), e.getZ());
        }

        Point3D calcTp(final Node source, final double x, final double y, final double z) {
            final Point3D sceneRawTp = source.localToScene(x, y, z);
            final double sceneDist = getTranslateZ();

            final Point3D sceneTp = sceneRawTp.multiply(sceneDist / sceneRawTp.getZ());
            try {
                return graphTransform.inverseTransform(sceneTp);
            } catch (NonInvertibleTransformException e1) {
                e1.printStackTrace();
                throw new RuntimeException(e1);
            }
        }

        NodeGeom(final io.tqi.ekg.Node node) {
            this.node = new WeakReference<>(node);

            getChildren().add(geom);

            body = new Ellipse(NODE_MAJ_RAD * GRAPH_SCALE, NODE_MIN_RAD * GRAPH_SCALE);
            body.setStrokeWidth(.01 * GRAPH_SCALE);
            body.setTranslateZ(-.5);
            geom.getChildren().add(body);

            text = new Label();
            text.setFont(new Font(.08 * GRAPH_SCALE));
            text.setLayoutX(-body.getRadiusX());
            text.setLayoutY(-body.getRadiusY());
            text.setAlignment(Pos.CENTER);
            text.setPrefWidth(2 * body.getRadiusX());
            text.setPrefHeight(2 * body.getRadiusY());
            text.setTranslateZ(-.6);
            geom.getChildren().add(text);

            getChildren().add(connections);

            updateNode();

            root.getChildren().add(this);
            node.rxChange().sample(1000 / 60, TimeUnit.MILLISECONDS).observeOn(JavaFxScheduler.platform())
                    .subscribe(x -> updateNode(), y -> {
                    }, () -> root.getChildren().remove(this));

            geom.setOnMousePressed(e -> {
                if (e.isSynthesized())
                    return;

                final io.tqi.ekg.Node n = this.node.get();
                if (n != null) {
                    dragPt = calcTp(e);
                    n.setPinned(true);
                    tappable.onActualMousePressed(e);
                } else {
                    tappable.cancelTap();
                }
            });

            geom.setOnTouchPressed(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (dragPtId == 0 && n != null) {
                    dragPtId = e.getTouchPoint().getId();
                    dragPt = calcTp(e);
                    n.setPinned(true);
                    tappable.onTouchPressed(e);
                } else {
                    tappable.cancelTap();
                }
            });

            geom.setOnMouseDragged(e -> {
                if (e.isSynthesized())
                    return;

                final io.tqi.ekg.Node n = this.node.get();

                if (n != null) {
                    if (tappable.onActualMouseDragged(e)) {
                        final Point3D newPt = calcTp(e);
                        n.setLocation(n.getLocation().add(newPt.subtract(dragPt)));
                        dragPt = newPt;
                    }
                } else {
                    tappable.cancelTap();
                }
            });

            geom.setOnTouchMoved(e -> {
                final io.tqi.ekg.Node n = this.node.get();

                if (dragPtId == e.getTouchPoint().getId() && n != null) {
                    if (tappable.onTouchMoved(e)) {
                        final Point3D newPt = calcTp(e);
                        n.setLocation(n.getLocation().add(newPt.subtract(dragPt)));
                        dragPt = newPt;
                    }
                } else {
                    tappable.cancelTap();
                }
            });

            geom.setOnMouseReleased(e -> {
                if (e.isSynthesized())
                    return;

                final io.tqi.ekg.Node n = this.node.get();
                if (n != null) {
                    n.setPinned(false);
                }
                if (tappable.onReleased()) {
                    touched(n == null ? null : this, n);
                }
            });

            geom.setOnTouchReleased(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (dragPtId == e.getTouchPoint().getId() && n != null) {
                    n.setPinned(false);
                    dragPtId = 0;
                }
                if (tappable.onReleased()) {
                    touched(n == null ? null : this, n);
                }
            });

            node.rxActivate()
                    .switchMap(t -> Observable.interval(1000 / 60, TimeUnit.MILLISECONDS)
                            .takeUntil(Observable.timer(1200, TimeUnit.MILLISECONDS)))
                    .observeOn(JavaFxScheduler.platform()).subscribe(t -> updateColor());

            rxCamOut.subscribe(e -> updatePosition());
        }

        void updateColor() {
            final io.tqi.ekg.Node n = node.get();
            if (n != null && n.getValue() == null) {
                body.setFill(Color.ALICEBLUE);
                body.setStroke(Color.CORNFLOWERBLUE);
            } else {
                body.setFill(Color.AQUAMARINE);
                body.setStroke(Color.CORNFLOWERBLUE);
            }

            if (n != null) {
                final double activation = Math.max(1000 + n.getLastActivation() - System.currentTimeMillis(), 0)
                        / 1000.0;
                if (activation > 0) {
                    body.setFill(((Color) body.getFill()).interpolate(Color.YELLOW, activation));
                    body.setStroke(((Color) body.getStroke()).interpolate(Color.YELLOW, activation));
                }
            }

            if (selected) {
                geom.setEffect(selEffect);
            } else {
                geom.setEffect(null);
            }
        }

        @Override
        public void select() {
            selected = true;
            updateColor();
        }

        @Override
        public void deselect() {
            selected = false;
            updateColor();
        }

        void updateNode() {
            final io.tqi.ekg.Node n = node.get();
            if (n != null) {
                text.setText(n.getValue() == null ? n.getComment() : n.getValue().toString());
                body.setRadiusX((Strings.isNullOrEmpty(text.getText()) ? NODE_MIN_RAD : NODE_MAJ_RAD) * GRAPH_SCALE);
                updateColor();

                if (n.getLocation() != null) {
                    updatePosition();
                    updateConnections();
                    for (final Iterator<Connection> it = incoming.iterator(); it.hasNext();) {
                        final Connection c = it.next();
                        if (c.getScene() == null)
                            it.remove();
                        else
                            c.update();
                    }

                    setVisible(true);
                } else {
                    setVisible(false);
                }
            } else {
                root.getChildren().remove(this);
            }
        }

        void updateConnections() {
            final io.tqi.ekg.Node n = node.get();
            if (n == null)
                return;

            connections.getChildren().clear();

            for (final Entry<io.tqi.ekg.Node, Activation> edge : n.getSynapse()) {
                final Connection connection = new Connection(node(edge.getKey()));
                connection.setColor(Color.RED, Color.TRANSPARENT);
                connections.getChildren().add(connection);
                connection.update();
            }
            for (final Entry<io.tqi.ekg.Node, io.tqi.ekg.Node> prop : n.getProperties().entrySet()) {
                if (prop.getValue() == null)
                    continue;
                final Connection connection = new PropConnection(node(prop.getKey()), node(prop.getValue()));
                connections.getChildren().add(connection);
                connection.update();
            }
        }

        void updatePosition() {
            final io.tqi.ekg.Node n = node.get();
            if (n != null && n.getLocation() != null) {
                final Point3D pos = graphTransform.transform(n.getLocation());
                setTranslateX(pos.getX());
                setTranslateY(pos.getY());
                setTranslateZ(pos.getZ());
            } else {
                setVisible(false);
            }

            updateConnections();
        }
    }

    private final Group root;
    private final PerspectiveCamera camera;
    private final List<Point2D> touchPoints = new ArrayList<>();
    private int navPtCount;
    private final Affine graphTransform;

    private final WeakHashMap<io.tqi.ekg.Node, NodeGeom> nodes = new WeakHashMap<>();

    private Selectable selectedUi;
    @Getter
    private Object selected;

    private Subject<Optional<Object>> rxSelected = PublishSubject.create();
    private Observable<Optional<Object>> rxSelectedOut = rxSelected.replay(1).autoConnect(0);

    public Observable<Optional<Object>> rxSelected() {
        return rxSelectedOut;
    }

    private Predicate<Object> selectFn;

    public void setSelectFn(final Predicate<Object> selectFn) {
        this.selectFn = selectFn;
        if (selectedUi != null) {
            if (selectFn != null && !selectFn.test(selected)) {
                selectedUi.deselect();
                selectedUi = null;
                selected = null;
            }
        }
    }

    private void touched(final Selectable ui, final Object data) {
        if (selectedUi != null) {
            selectedUi.deselect();
            selectedUi = null;
            selected = null;
        }
        if (ui != null && (selectFn == null || selectFn.test(data))) {
            selectedUi = ui;
            selected = data;
            selectedUi.select();
        }
        rxSelected.onNext(Optional.ofNullable(selected));
    }

    private final Subject<Optional<Void>> rxCam = PublishSubject.create();
    private final Observable<Optional<Void>> rxCamOut = rxCam.sample(1000 / 60, TimeUnit.MILLISECONDS)
            .observeOn(JavaFxScheduler.platform()).share();

    public GraphPanel(final KnowledgeBase kb) {
        root = new Group();

        camera = new PerspectiveCamera(true);
        camera.setFieldOfView(40);
        camera.setNearClip(1);
        camera.setFarClip(100000);
        root.getChildren().add(camera);

        graphTransform = new Affine();
        graphTransform.append(new Scale(GRAPH_SCALE, GRAPH_SCALE, GRAPH_SCALE));

        // Stagger node creation or else JavaFX may NPE on cache buffer
        // creation...
        Observable.fromIterable(kb).concatWith(kb.rxNodeAdded())
                .zipWith(Observable.interval(2, TimeUnit.MILLISECONDS), (n, t) -> n)
                .observeOn(JavaFxScheduler.platform()).subscribe(this::node);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (final io.tqi.ekg.Node node : kb) {
            final Point3D pt = node.getLocation();
            if (pt == null)
                continue;

            minX = Math.min(pt.getX(), minX);
            maxX = Math.max(pt.getX(), maxX);
            minY = Math.min(pt.getY(), minY);
            maxY = Math.max(pt.getY(), maxY);
            minZ = Math.min(pt.getZ(), minZ);
            maxZ = Math.max(pt.getZ(), maxZ);
        }

        minX *= GRAPH_SCALE;
        maxX *= GRAPH_SCALE;
        minY *= GRAPH_SCALE;
        maxY *= GRAPH_SCALE;
        minZ *= GRAPH_SCALE;
        maxZ *= GRAPH_SCALE;

        graphTransform.setTx(-(maxX + minX) / 2);
        graphTransform.setTy(-(maxY + minY) / 2);
        graphTransform
                .setTz(Math.max(maxX - minX, maxY - minY) / 2 / Math.tan(Math.toRadians(camera.getFieldOfView())));

        for (final NodeGeom geom : nodes.values()) {
            geom.updatePosition();
        }

        final SubScene scene = new SubScene(root, 0, 0, true, SceneAntialiasing.BALANCED);
        scene.setCamera(camera);
        getChildren().add(scene);
        scene.widthProperty().bind(widthProperty());
        scene.heightProperty().bind(heightProperty());

        touchPoints.add(null);

        setTouchHandlers();

        rxSelected.onNext(Optional.empty());
    }

    private NodeGeom node(final io.tqi.ekg.Node node) {
        assert (node != null);
        return nodes.computeIfAbsent(node, NodeGeom::new);
    }

    private static class TouchMove {
        final Point2D oldPt, newPt, delta;

        public TouchMove(final Point2D oldPt, final double x, final double y) {
            this.oldPt = oldPt;
            newPt = new Point2D(x, y);
            delta = newPt.subtract(oldPt);
        }
    }

    private TouchMove recordMove(final int ptId, final double x, final double y) {
        final TouchMove tm = new TouchMove(touchPoints.get(ptId), x, y);
        touchPoints.set(ptId, tm.newPt);
        return tm;
    }

    private void setTouchHandlers() {
        final Tappable tappable = new Tappable();

        setOnMousePressed(e -> {
            requestFocus();

            if (e.isSynthesized())
                return;

            touchPoints.set(0, new Point2D(e.getScreenX(), e.getScreenY()));

            if (e.getTarget() == this) {
                tappable.onActualMousePressed(e);
            }

            e.consume();
        });

        setOnTouchPressed(e -> {
            final TouchPoint t = e.getTouchPoint();
            while (touchPoints.size() < t.getId())
                touchPoints.add(null);
            touchPoints.set(t.getId() - 1, new Point2D(t.getScreenX(), t.getScreenY()));

            if (e.getTarget() == this) {
                navPtCount++;
                tappable.onTouchPressed(e);
            }

            e.consume();
        });

        setOnMouseReleased(e -> {
            if (!e.isSynthesized() && e.getTarget() == this) {
                if (tappable.onReleased()) {
                    touched(null, null);
                }
            }
            e.consume();
        });

        setOnTouchReleased(e -> {
            if (e.getTarget() == this) {
                navPtCount--;
                if (tappable.onReleased()) {
                    touched(null, null);
                }
            }
            e.consume();
        });

        setOnMouseDragged(e -> {
            if (!e.isSynthesized() && e.getTarget() == this && tappable.onActualMouseDragged(e)) {
                final TouchMove tm = recordMove(0, e.getScreenX(), e.getScreenY());
                if (e.isPrimaryButtonDown() && !e.isControlDown() && !e.isShiftDown()) {
                    onTranslateDrag(tm.delta);
                }
                if (e.isSecondaryButtonDown() || e.isControlDown()) {
                    onRotateDrag(tm.delta);
                }
                if (e.isMiddleButtonDown() || e.isShiftDown()) {
                    graphTransform.prependTranslation(0, 0, ZOOM_FACTOR * tm.delta.getY());
                }
                rxCam.onNext(Optional.empty());
            }
            e.consume();
        });

        setOnTouchMoved(e -> {
            if (tappable.onTouchMoved(e)) {
                final TouchPoint t = e.getTouchPoint();
                final TouchMove tm = recordMove(t.getId() - 1, t.getScreenX(), t.getScreenY());

                if (navPtCount > 0) {
                    if (e.getTouchCount() == 1) {
                        onRotateDrag(tm.delta);
                    } else {
                        final double normFactor = 1.0 / e.getTouchCount();
                        onTranslateDrag(tm.delta.multiply(normFactor));

                        Point2D centroid = new Point2D(0, 0);

                        for (final TouchPoint pt : e.getTouchPoints()) {
                            centroid = centroid.add(new Point2D(pt.getScreenX(), pt.getScreenY()).multiply(normFactor));
                        }

                        final Point2D toOld = centroid.subtract(tm.oldPt), toNew = centroid.subtract(tm.newPt);

                        graphTransform.prependTranslation(0, 0, ZOOM_FACTOR * tm.delta.dotProduct(toOld.normalize()));

                        graphTransform.prependRotation(toOld.angle(toNew) / e.getTouchCount(), Point3D.ZERO,
                                toOld.crossProduct(toNew).normalize());
                    }

                    rxCam.onNext(Optional.empty());
                }
            }
            e.consume();
        });
    }

    private void onRotateDrag(final Point2D delta) {
        double screenNorm = 1 / Math.min(getWidth(), getHeight());
        final Point3D from3 = new Point3D(0, 0, 1 / ROTATION_FACTOR);
        final Point3D to3 = new Point3D(delta.getX() * screenNorm, delta.getY() * screenNorm, 1 / ROTATION_FACTOR);

        final Point3D vc = getViewCentroid();
        final double angle = to3.angle(from3);

        graphTransform.prependRotation(vc.equals(Point3D.ZERO) ? -angle : angle, vc,
                to3.crossProduct(from3).normalize());
    }

    private void onTranslateDrag(final Point2D delta) {
        final Point2D adjusted = delta.multiply(TRANSLATION_FACTOR);
        graphTransform.prependTranslation(adjusted.getX(), adjusted.getY());
    }

    private Point3D getViewCentroid() {
        final double snorm = Math.tan(Math.toRadians(camera.getFieldOfView())) / Math.min(getWidth(), getHeight());
        final double xLimit = snorm * getWidth(), yLimit = snorm * getHeight();
        Point3D centroid = Point3D.ZERO;
        double n = 0;
        for (final NodeGeom node : nodes.values()) {
            final Point3D crpt = camera.sceneToLocal(node.localToScene(Point3D.ZERO));
            if (crpt.getZ() <= camera.getFarClip() && crpt.getZ() >= camera.getNearClip()) {
                if (Math.abs(crpt.getX() / crpt.getZ()) <= xLimit && Math.abs(crpt.getY() / crpt.getZ()) <= yLimit) {
                    double w = 1 / crpt.getZ();
                    centroid = centroid.add(crpt.multiply(w));
                    n += w;
                }
            }
        }

        if (n > 0) {
            centroid = centroid.multiply(1 / n);
        }

        return centroid;
    }
}
