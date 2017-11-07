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
    private static final double TRANSLATION_FACTOR = 12;
    private static final double ZOOM_FACTOR = 5;
    private static final double GRAPH_SCALE = 650;

    private class Connection extends Group {
        final NodeGeom end;
        final Line line = new Line();
        final Rotate rotate1 = new Rotate(0, Rotate.Z_AXIS), rotate2 = new Rotate(0, Rotate.Y_AXIS);

        Connection(final NodeGeom end) {
            this.end = end;
            end.incoming.add(this);
            line.setStrokeWidth(4);
            line.getTransforms().add(rotate1);
            line.getTransforms().add(rotate2);
            getChildren().add(line);
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

    private class PropConnection extends Connection {
        final Connection spur;

        PropConnection(final NodeGeom property, final NodeGeom value) {
            super(value);
            setColor(Color.TRANSPARENT, Color.BLACK);
            spur = new Connection(property);
            spur.setColor(Color.BLUE, Color.TRANSPARENT);
            getChildren().add(spur);
        }

        @Override
        void update() {
            super.update();
            final Point3D midpt = sceneToLocal(end.localToScene(Point3D.ZERO)).multiply(.5);
            spur.setTranslateX(midpt.getX());
            spur.setTranslateY(midpt.getY());
            spur.setTranslateZ(midpt.getZ());
            spur.update();
        }
    }

    private static final int DRAG_START = 8;

    private class NodeGeom extends Group {
        final WeakReference<io.tqi.ekg.Node> node;
        final Ellipse body;
        final Label text;

        final Group geom = new Group();
        final Group connections = new Group();
        final Set<Connection> incoming = new HashSet<>();

        final InnerShadow selEffect = new InnerShadow(256, Color.DEEPSKYBLUE);

        int dragPtId;
        Point3D dragPt;
        Point2D preDrag;

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

            body = new Ellipse(280, 80);
            body.setStrokeWidth(4);
            body.setTranslateZ(-1);
            geom.getChildren().add(body);

            text = new Label();
            text.setFont(new Font(50));
            text.setLayoutX(-250);
            text.setLayoutY(-50);
            text.setAlignment(Pos.CENTER);
            text.setPrefWidth(500);
            text.setPrefHeight(100);
            text.setTranslateZ(-4);
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
                    preDrag = new Point2D(e.getScreenX(), e.getScreenY());
                } else {
                    preDrag = null;
                }
            });

            geom.setOnTouchPressed(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (dragPtId == 0 && n != null) {
                    dragPtId = e.getTouchPoint().getId();
                    dragPt = calcTp(e);
                    n.setPinned(true);

                    if (e.getTouchCount() == 1) {
                        preDrag = new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY());
                    } else {
                        preDrag = null;
                    }
                } else {
                    preDrag = null;
                }
            });

            geom.setOnMouseDragged(e -> {
                if (e.isSynthesized())
                    return;

                final io.tqi.ekg.Node n = this.node.get();

                if (n != null) {
                    if (preDrag == null || new Point2D(e.getScreenX(), e.getScreenY())
                            .distance(preDrag) >= DRAG_START) {
                        final Point3D newPt = calcTp(e);
                        n.setLocation(n.getLocation().add(newPt.subtract(dragPt)));
                        dragPt = newPt;
                        preDrag = null;
                    }
                } else {
                    preDrag = null;
                }
            });

            geom.setOnTouchMoved(e -> {
                final io.tqi.ekg.Node n = this.node.get();

                if (dragPtId == e.getTouchPoint().getId() && n != null) {
                    if (preDrag == null || new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY())
                            .distance(preDrag) >= DRAG_START) {
                        final Point3D newPt = calcTp(e);
                        n.setLocation(n.getLocation().add(newPt.subtract(dragPt)));
                        dragPt = newPt;
                        preDrag = null;
                    }
                } else {
                    preDrag = null;
                }
            });

            geom.setOnMouseReleased(e -> {
                if (e.isSynthesized())
                    return;

                final io.tqi.ekg.Node n = this.node.get();
                if (n != null) {
                    n.setPinned(false);
                }
                if (preDrag != null) {
                    preDrag = null;
                    rxSelected.onNext(Optional.of(n));
                }
            });

            geom.setOnTouchReleased(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (dragPtId == e.getTouchPoint().getId() && n != null) {
                    n.setPinned(false);
                    dragPtId = 0;
                }
                if (preDrag != null) {
                    preDrag = null;
                    rxSelected.onNext(Optional.of(n));
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

            if (selected == node.get()) {
                geom.setEffect(selEffect);
            } else {
                geom.setEffect(null);
            }
        }

        void updateNode() {
            final io.tqi.ekg.Node n = node.get();
            if (n != null) {
                text.setText(n.getValue() == null ? n.getComment() : n.getValue().toString());
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

    private final Subject<Optional<io.tqi.ekg.Node>> rxSelected = PublishSubject.create();
    private final Observable<Optional<io.tqi.ekg.Node>> rxSelectedOut = rxSelected.replay(1).autoConnect(0);

    public Observable<Optional<io.tqi.ekg.Node>> rxSelected() {
        return rxSelectedOut;
    }

    @Getter
    private io.tqi.ekg.Node selected;

    private final Subject<Optional<Void>> rxCam = PublishSubject.create();
    private final Observable<Optional<Void>> rxCamOut = rxCam.sample(1000 / 120, TimeUnit.MILLISECONDS)
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

        rxSelected.onNext(Optional.empty());
        for (final NodeGeom geom : nodes.values()) {
            geom.updatePosition();
        }

        final SubScene scene = new SubScene(root, 0, 0, true, SceneAntialiasing.BALANCED);
        scene.setCamera(camera);
        getChildren().add(scene);
        scene.widthProperty().bind(widthProperty());
        scene.heightProperty().bind(heightProperty());

        setTouchHandlers();

        rxSelected().distinctUntilChanged().subscribe(n -> {
            final NodeGeom oldSel = node(selected);
            selected = n.orElse(null);
            final NodeGeom newSel = node(selected);

            if (oldSel != null)
                oldSel.updateColor();
            if (newSel != null)
                newSel.updateColor();
        });
    }

    private NodeGeom node(final io.tqi.ekg.Node node) {
        return node == null ? null : nodes.computeIfAbsent(node, NodeGeom::new);
    }

    private Point2D preDrag;

    private void setTouchHandlers() {
        setOnMousePressed(e -> {
            requestFocus();
        });

        setOnTouchPressed(e -> {
            final TouchPoint t = e.getTouchPoint();
            while (touchPoints.size() < t.getId())
                touchPoints.add(null);
            touchPoints.set(t.getId() - 1,
                    new Point2D(t.getSceneX() - getWidth() / 2, t.getSceneY() - getHeight() / 2));

            if (e.getTarget() == this) {
                navPtCount++;
                if (e.getTouchCount() == 1) {
                    preDrag = new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY());
                }
            }

            e.consume();
        });

        setOnTouchReleased(e -> {
            touchPoints.set(e.getTouchPoint().getId() - 1, null);

            if (e.getTarget() == this) {
                navPtCount--;
            }
            if (preDrag != null) {
                preDrag = null;
                rxSelected.onNext(Optional.empty());
            }

            e.consume();
        });

        setOnTouchMoved(e -> {
            if (preDrag != null && new Point2D(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY())
                    .distance(preDrag) >= DRAG_START) {
                preDrag = null;
            }

            if (preDrag == null) {
                final TouchPoint t = e.getTouchPoint();
                final Point2D newPt = new Point2D(t.getSceneX() - getWidth() / 2, t.getSceneY() - getHeight() / 2);

                if (navPtCount > 0) {
                    if (e.getTouchCount() == 1) {
                        double screenNorm = 1 / Math.min(getWidth(), getHeight());
                        final Point2D delta = newPt.subtract(touchPoints.get(t.getId() - 1));
                        final Point3D from3 = new Point3D(0, 0, 1 / ROTATION_FACTOR);
                        final Point3D to3 = new Point3D(delta.getX() * screenNorm, delta.getY() * screenNorm,
                                1 / ROTATION_FACTOR);

                        final Point3D vc = getViewCentroid();
                        final double angle = to3.angle(from3);

                        graphTransform.prependRotation(vc.equals(Point3D.ZERO) ? -angle : angle, vc,
                                to3.crossProduct(from3).normalize());
                    } else {
                        final Point2D oldPt = touchPoints.get(t.getId() - 1);
                        final Point2D delta = newPt.subtract(oldPt).multiply(TRANSLATION_FACTOR / e.getTouchCount());
                        graphTransform.prependTranslation(delta.getX(), delta.getY());

                        Point2D centroid = new Point2D(0, 0);

                        for (final TouchPoint pt : e.getTouchPoints()) {
                            centroid = centroid
                                    .add(new Point2D(pt.getSceneX() - getWidth() / 2, pt.getSceneY() - getHeight() / 2)
                                            .multiply(1.0 / e.getTouchCount()));
                        }

                        final Point2D toOld = centroid.subtract(oldPt), toNew = centroid.subtract(newPt);

                        graphTransform.prependTranslation(0, 0, ZOOM_FACTOR * delta.dotProduct(toOld.normalize()));

                        graphTransform.prependRotation(toOld.angle(toNew) / e.getTouchCount(), Point3D.ZERO,
                                toOld.crossProduct(toNew).normalize());
                    }

                    rxCam.onNext(Optional.empty());
                }

                touchPoints.set(t.getId() - 1, newPt);
            }
            e.consume();
        });
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
