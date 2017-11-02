package io.tqi.ekg.ifc.fx;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Node;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.input.TouchPoint;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;
import javafx.scene.transform.NonInvertibleTransformException;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;

public class GraphPanel {
    private static final double ROTATION_FACTOR = 2;
    private static final double TRANSLATION_FACTOR = 1.5;
    private static final double ZOOM_FACTOR = 5;
    private static final double GRAPH_SCALE = 100;
    private static final double GEOM_SCALE = .15;

    private class NodeGeom extends Group {
        final WeakReference<io.tqi.ekg.Node> node;
        final Group billboard, geom;
        final Ellipse body;
        final Text text;

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

            updateNode();
            node.rxChange().subscribe(o -> {
                Platform.runLater(this::updateNode);
            });

            graph.getChildren().add(this);
            node.rxChange().subscribe(x -> {
            }, y -> {
            }, () -> Platform.runLater(() -> graph.getChildren().remove(this)));

            updateBillboard();
        }

        void updateNode() {
            if (node.get() != null) {
                final Node n = node.get();
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
                    setVisible(true);
                } else {
                    setVisible(false);
                }
            } else {
                setVisible(false);
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
        }
    }

    private final Group root, graph;
    private final PerspectiveCamera camera;
    private final List<Point2D> touchPoints = new ArrayList<>();
    private final Affine cameraAnchor;

    private final WeakHashMap<io.tqi.ekg.Node, NodeGeom> nodes = new WeakHashMap<>();

    public GraphPanel(final KnowledgeBase kb) {
        root = new Group();

        cameraAnchor = new Affine();
        cameraAnchor.setTz(-400);
        camera = new PerspectiveCamera(true);
        camera.setFieldOfView(40);
        camera.setNearClip(1);
        camera.setFarClip(10000);
        camera.getTransforms().add(cameraAnchor);
        root.getChildren().add(camera);

        graph = new Group();
        graph.getTransforms().add(new Scale(GRAPH_SCALE, GRAPH_SCALE, GRAPH_SCALE));
        root.getChildren().add(graph);

        kb.rxNodeAdded().subscribe(this::addNode);
        for (final io.tqi.ekg.Node node : kb) {
            addNode(node);
        }

        cameraAnchor.setOnTransformChanged(e -> {
            for (final NodeGeom geom : nodes.values()) {
                geom.updateBillboard();
            }
        });
    }

    private void addNode(final Node node) {
        Platform.runLater(() -> nodes.computeIfAbsent(node, NodeGeom::new));
    }

    public Scene createScene() {
        final Scene scene = new Scene(root, 0, 0, true, SceneAntialiasing.BALANCED);
        scene.setCamera(camera);

        scene.setOnTouchPressed(e -> {
            final TouchPoint t = e.getTouchPoint();
            while (touchPoints.size() < t.getId())
                touchPoints.add(null);
            touchPoints.set(t.getId() - 1,
                    new Point2D(t.getSceneX() - scene.getWidth() / 2, t.getSceneY() - scene.getHeight() / 2));
            e.consume();
        });

        scene.setOnTouchReleased(e -> {
            touchPoints.set(e.getTouchPoint().getId() - 1, null);
            e.consume();
        });

        scene.setOnTouchMoved(e -> {
            final TouchPoint t = e.getTouchPoint();
            final Point2D newPt = new Point2D(t.getSceneX() - scene.getWidth() / 2,
                    t.getSceneY() - scene.getHeight() / 2);

            if (e.getTouchCount() == 1) {
                double screenNorm = 1 / Math.min(scene.getWidth(), scene.getHeight());
                final Point2D delta = newPt.subtract(touchPoints.get(t.getId() - 1));
                final Point3D from3 = new Point3D(0, 0, 1 / ROTATION_FACTOR);
                final Point3D to3 = new Point3D(delta.getX() * screenNorm, delta.getY() * screenNorm,
                        1 / ROTATION_FACTOR);

                cameraAnchor.appendRotation(to3.angle(from3), Point3D.ZERO, to3.crossProduct(from3).normalize());
            } else {
                final Point2D oldPt = touchPoints.get(t.getId() - 1);
                final Point2D delta = newPt.subtract(oldPt).multiply(-TRANSLATION_FACTOR / e.getTouchCount());
                cameraAnchor.appendTranslation(delta.getX(), delta.getY());

                Point2D centroid = new Point2D(0, 0);

                for (final TouchPoint pt : e.getTouchPoints()) {
                    centroid = centroid.add(
                            new Point2D(pt.getSceneX() - scene.getWidth() / 2, pt.getSceneY() - scene.getHeight() / 2)
                                    .multiply(1.0 / e.getTouchCount()));
                }

                final Point2D toOld = oldPt.subtract(centroid), toNew = newPt.subtract(centroid);

                cameraAnchor.appendTranslation(0, 0, -ZOOM_FACTOR * delta.dotProduct(toOld.normalize()));

                cameraAnchor.appendRotation(-toOld.angle(toNew) / e.getTouchCount(), Point3D.ZERO,
                        toOld.crossProduct(toNew).normalize());
            }

            touchPoints.set(t.getId() - 1, newPt);
            e.consume();
        });

        return scene;
    }
}
