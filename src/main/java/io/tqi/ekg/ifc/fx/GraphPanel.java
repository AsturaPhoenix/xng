package io.tqi.ekg.ifc.fx;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;

import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Synapse.Activation;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;
import javafx.scene.transform.NonInvertibleTransformException;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;

public class GraphPanel extends StackPane {
    private static final double ROTATION_FACTOR = 2;
    private static final double TRANSLATION_FACTOR = 1.5;
    private static final double ZOOM_FACTOR = 5;
    private static final double GRAPH_SCALE = 100;
    private static final double GEOM_SCALE = .15;

    private static final Point3D X = new Point3D(1, 0, 0);

    private class Connection extends Group {
        final NodeGeom end;
        final Line line;
        final Rotate rotate;

        Connection(final NodeGeom end) {
            this.end = end;
            end.incoming.add(this);
            line = new Line();
            line.setStrokeWidth(4);
            rotate = new Rotate();
            line.getTransforms().add(rotate);
            getChildren().add(line);
        }

        void setColor(final Color color1, Color color2) {
            line.setStroke(new LinearGradient(0, 0, .05, 0, true, CycleMethod.REPEAT, new Stop(0, color1),
                    new Stop(1, color2)));
        }

        void update() {
            final Point3D delta = sceneToLocal(end.localToScene(Point3D.ZERO));
            line.setEndX(delta.magnitude());
            rotate.setAngle(X.angle(delta));
            rotate.setAxis(X.crossProduct(delta));
        }
    }

    private class PropConnection extends Connection {
        final Connection spur;

        PropConnection(final NodeGeom property, final NodeGeom value) {
            super(value);
            setColor(Color.BLACK, Color.TRANSPARENT);
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

    private class NodeGeom extends Group {
        final WeakReference<io.tqi.ekg.Node> node;
        final Group billboard, geom;
        final Ellipse body;
        final Text text;

        final Group connections = new Group();
        final Set<Connection> incoming = new HashSet<>();

        int dragPtId;
        Point3D dragPt;

        Point3D calcTp(final TouchEvent e) {
            final TouchPoint pt = e.getTouchPoint();
            final Point3D bbCamPt = billboard.sceneToLocal(camera.localToScene(Point3D.ZERO));
            final Point3D bbTp = billboard
                    .sceneToLocal(((Node) e.getSource()).localToScene(pt.getX(), pt.getY(), pt.getZ()));
            final Point3D ray = bbTp.subtract(bbCamPt);

            return localToParent(
                    sceneToLocal(billboard.localToScene(bbTp.getX() - ray.getX() / ray.getZ() * bbTp.getZ(),
                            bbTp.getY() - ray.getY() / ray.getZ() * bbTp.getZ(), 0)));
        }

        NodeGeom(final io.tqi.ekg.Node node) {
            this.node = new WeakReference<>(node);

            billboard = new Group();
            getChildren().add(billboard);

            geom = new Group();
            billboard.getChildren().add(geom);

            body = new Ellipse(280, 80);
            body.setStrokeWidth(4);
            geom.getChildren().add(body);

            text = new Text();
            text.setFont(new Font(50));
            text.setTextAlignment(TextAlignment.CENTER);
            text.setTextOrigin(VPos.CENTER);
            text.setTranslateZ(-4);
            geom.getChildren().add(text);

            geom.getTransforms().add(new Scale(GEOM_SCALE, GEOM_SCALE, GEOM_SCALE));

            geom.getChildren().add(connections);

            updateNode();
            node.rxChange().subscribe(o -> {
                Platform.runLater(this::updateNode);
            });

            graph.getChildren().add(this);
            node.rxChange().subscribe(x -> {
            }, y -> {
            }, () -> Platform.runLater(() -> graph.getChildren().remove(this)));

            updateBillboard();

            setOnTouchPressed(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (dragPtId == 0 && n != null) {
                    dragPtId = e.getTouchPoint().getId();
                    dragPt = calcTp(e);
                    n.setPinned(true);
                }
            });

            setOnTouchMoved(e -> {
                final io.tqi.ekg.Node n = this.node.get();

                if (dragPtId == e.getTouchPoint().getId() && n != null) {
                    final Point3D newPt = calcTp(e);
                    n.setLocation(n.getLocation().add(newPt.subtract(dragPt)));
                    dragPt = newPt;
                }
            });

            setOnTouchReleased(e -> {
                final io.tqi.ekg.Node n = this.node.get();
                if (n != null)
                    n.setPinned(false);
                dragPtId = 0;
            });
        }

        void updateNode() {
            if (node.get() != null) {
                final io.tqi.ekg.Node n = node.get();
                if (n.getValue() == null) {
                    text.setText(n.getComment());
                    body.setFill(Color.ALICEBLUE);
                    body.setStroke(Color.CORNFLOWERBLUE);
                } else {
                    text.setText(n.getValue().toString());
                    body.setFill(Color.AQUAMARINE);
                    body.setStroke(Color.CORNFLOWERBLUE);
                }

                text.setTranslateX(-text.getLayoutBounds().getWidth() / 2);

                if (n.getLocation() != null) {
                    setTranslateX(n.getLocation().getX());
                    setTranslateY(n.getLocation().getY());
                    setTranslateZ(n.getLocation().getZ());

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
                graph.getChildren().remove(this);
            }
        }

        void updateConnections() {
            final io.tqi.ekg.Node n = node.get();
            if (n == null)
                return;

            connections.getChildren().clear();

            for (final Entry<io.tqi.ekg.Node, Activation> edge : n.getSynapse()) {
                final Connection connection = new Connection(node(edge.getKey()));
                connection.setColor(Color.TRANSPARENT, Color.RED);
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

        void updateBillboard() {
            try {
                final Transform m = camera.localToSceneTransformProperty().get()
                        .createConcatenation(localToSceneTransformProperty().get().createInverse());

                // @formatter:off
                billboard.getTransforms().setAll(new Affine(
                        m.getMxx(), m.getMxy(), m.getMxz(), 0,
                        m.getMyx(), m.getMyy(), m.getMyz(), 0,
                        m.getMzx(), m.getMzy(), m.getMzz(), 0));
                // @formatter:on
            } catch (final NonInvertibleTransformException e) {
                throw new RuntimeException(e);
            }

            updateConnections();
        }
    }

    private final Group root, graph;
    private final PerspectiveCamera camera;
    private final List<Point2D> touchPoints = new ArrayList<>();
    private int navPtCount;
    private final Affine cameraAnchor;

    private final WeakHashMap<io.tqi.ekg.Node, NodeGeom> nodes = new WeakHashMap<>();

    public GraphPanel(final KnowledgeBase kb) {
        root = new Group();

        cameraAnchor = new Affine();
        camera = new PerspectiveCamera(true);
        camera.setFieldOfView(40);
        camera.setNearClip(1);
        camera.setFarClip(10000);
        camera.getTransforms().add(cameraAnchor);
        root.getChildren().add(camera);

        graph = new Group();
        graph.getTransforms().add(new Scale(GRAPH_SCALE, GRAPH_SCALE, GRAPH_SCALE));
        root.getChildren().add(graph);

        kb.rxNodeAdded().observeOn(JavaFxScheduler.platform()).subscribe(this::node);
        for (final io.tqi.ekg.Node node : kb) {
            node(node);
        }

        cameraAnchor.setOnTransformChanged(e -> {
            for (final NodeGeom geom : nodes.values()) {
                geom.updateBillboard();
            }
        });

        final SubScene scene = new SubScene(root, 0, 0, true, SceneAntialiasing.BALANCED);
        scene.setCamera(camera);
        getChildren().add(scene);
        scene.widthProperty().bind(widthProperty());
        scene.heightProperty().bind(heightProperty());

        final Bounds graphBounds = graph.getBoundsInParent();
        cameraAnchor.setTx((graphBounds.getMaxX() + graphBounds.getMinX()) / 2);
        cameraAnchor.setTy((graphBounds.getMaxY() + graphBounds.getMinY()) / 2);
        cameraAnchor.setTz(-Math.max(graphBounds.getWidth(), graphBounds.getHeight()) / 2
                / Math.tan(Math.toRadians(camera.getFieldOfView())));

        setTouchHandlers();
    }

    private NodeGeom node(final io.tqi.ekg.Node node) {
        return nodes.computeIfAbsent(node, NodeGeom::new);
    }

    private void setTouchHandlers() {
        setOnTouchPressed(e -> {
            final TouchPoint t = e.getTouchPoint();
            while (touchPoints.size() < t.getId())
                touchPoints.add(null);
            touchPoints.set(t.getId() - 1,
                    new Point2D(t.getSceneX() - getWidth() / 2, t.getSceneY() - getHeight() / 2));

            if (e.getTarget() == this) {
                navPtCount++;
            }

            e.consume();
        });

        setOnTouchReleased(e -> {
            touchPoints.set(e.getTouchPoint().getId() - 1, null);

            if (e.getTarget() == this) {
                navPtCount--;
            }

            e.consume();
        });

        setOnTouchMoved(e -> {
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

                    cameraAnchor.appendRotation(vc.equals(Point3D.ZERO) ? angle : -angle, vc,
                            to3.crossProduct(from3).normalize());
                } else {
                    final Point2D oldPt = touchPoints.get(t.getId() - 1);
                    final Point2D delta = newPt.subtract(oldPt).multiply(-TRANSLATION_FACTOR / e.getTouchCount());
                    cameraAnchor.appendTranslation(delta.getX(), delta.getY());

                    Point2D centroid = new Point2D(0, 0);

                    for (final TouchPoint pt : e.getTouchPoints()) {
                        centroid = centroid
                                .add(new Point2D(pt.getSceneX() - getWidth() / 2, pt.getSceneY() - getHeight() / 2)
                                        .multiply(1.0 / e.getTouchCount()));
                    }

                    final Point2D toOld = oldPt.subtract(centroid), toNew = newPt.subtract(centroid);

                    cameraAnchor.appendTranslation(0, 0, -ZOOM_FACTOR * delta.dotProduct(toOld.normalize()));

                    cameraAnchor.appendRotation(-toOld.angle(toNew) / e.getTouchCount(), Point3D.ZERO,
                            toOld.crossProduct(toNew).normalize());
                }
            }

            touchPoints.set(t.getId() - 1, newPt);
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