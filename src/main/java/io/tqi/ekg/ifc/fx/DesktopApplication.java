package io.tqi.ekg.ifc.fx;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.tqi.ekg.ChangeObservable;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.KnowledgeBase.Common;
import io.tqi.ekg.Node;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import io.tqi.ekg.ifc.fx.GraphPanel.Association;
import io.tqi.ekg.ifc.fx.GraphPanel.Property;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class DesktopApplication extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    private final KnowledgeBase kb;
    private final Repl repl;
    private GraphPanel graph;
    private TextField input;

    public DesktopApplication() throws FileNotFoundException, ClassNotFoundException, ClassCastException, IOException {
        this.kb = SerializingPersistence.loadBound(FileSystems.getDefault().getPath("persistence"));

        repl = new Repl(kb);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        primaryStage.setTitle("EKG");

        graph = new GraphPanel(kb);

        final javafx.scene.Node console = createConsole();
        final SplitPane splitPane = new SplitPane(graph, console);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(.5);
        SplitPane.setResizableWithParent(console, false);

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(createToolbar());
        borderPane.setCenter(splitPane);
        borderPane.setRight(createDetails());
        final Scene scene = new Scene(borderPane);
        primaryStage.setScene(scene);

        primaryStage.setMaximized(true);
        primaryStage.show();
        input.requestFocus();

        primaryStage.setOnCloseRequest(e -> kb.close());
    }

    private void initButton(final ButtonBase button, final String res, final String altText) {
        try {
            button.setGraphic(new ImageView(new Image(new FileInputStream(res))));
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            button.setText(altText);
        }
        button.setTooltip(new Tooltip(altText));
    }

    private Node findNode(final String query) {
        return kb.getNode(query);
    }

    private void selectNode(final String query) {
        graph.select(findNode(query));
    }

    private final Button delete = new Button();
    private final ToggleButton property = new ToggleButton();
    private final TextField text = new TextField();
    private final Tooltip propTt = new Tooltip();

    private interface Mode {
        void bind();

        void unbind();
    }

    private Mode mode;

    private void setMode(final Mode mode) {
        if (this.mode != null)
            this.mode.unbind();
        this.mode = mode;
        if (mode != null)
            mode.bind();
    }

    private void setFindNodeOnText() {
        text.setOnAction(e -> selectNode(text.getText()));
    }

    private final Mode defaultMode = new Mode() {
        private Disposable onSelected;
        private ChangeListener<String> textListener;

        @Override
        public void bind() {
            onSelected = graph.rxSelected().subscribe(this::onSelected);
        }

        @Override
        public void unbind() {
            if (textListener != null)
                text.textProperty().removeListener(textListener);
            onSelected.dispose();
            text.setOnAction(null);
        }

        void onSelected(final Optional<Object> selection) {
            if (textListener != null)
                text.textProperty().removeListener(textListener);
            if (selection.isPresent()) {
                delete.setDisable(false);
                text.setOnAction(null);
                if (selection.get() instanceof Node) {
                    final Node node = (Node) selection.get();
                    text.setText(node.getComment());
                    text.positionCaret(text.getLength());

                    text.setPromptText("Comment");
                    textListener = (o, a, b) -> node.setComment(b);
                    text.textProperty().addListener(textListener);
                }
            } else {
                text.setPromptText("Find/create node");
                text.clear();
                setFindNodeOnText();
            }
        }
    };

    private final Mode selectMode = new Mode() {
        private Disposable onSelected;

        @Override
        public void bind() {
            onSelected = graph.rxSelected().subscribe(o -> text.clear());
            setFindNodeOnText();
        }

        @Override
        public void unbind() {
            onSelected.dispose();
            text.setOnAction(null);
        }
    };

    private final Mode activateMode = new Mode() {
        @Override
        public void bind() {
            selectMode.bind();
            text.setPromptText("Activate");

            graph.setSelectFn(o -> {
                if (o instanceof Node) {
                    ((Node) o).activate();
                    return true;
                } else {
                    return false;
                }
            });
        }

        @Override
        public void unbind() {
            selectMode.unbind();
            graph.setSelectFn(null);
        }
    };

    private static final String[] PROP_PARAMS = { "Object", "Property", "Value" };

    private final Mode propertyMode = new Mode() {
        final List<Node> args = new ArrayList<>();
        final StringBuilder ttBuilder = new StringBuilder();

        @Override
        public void bind() {
            selectMode.bind();
            updateForArgs();

            graph.setSelectFn(o -> {
                if (o instanceof Node) {
                    final Node node = (Node) o;
                    args.add(node);
                    ttBuilder.append(node.displayString());
                    switch (args.size()) {
                    case 1:
                        ttBuilder.append('.');
                        break;
                    case 2:
                        ttBuilder.append('=');
                        break;
                    case 3:
                        args.get(0).setProperty(args.get(1), args.get(2));

                        property.setSelected(false);
                        setMode(defaultMode);
                        return true;
                    }

                    updateForArgs();
                    text.requestFocus();

                    return true;
                } else {
                    return false;
                }
            });

            propTt.show(graph.getScene().getWindow());
        }

        @Override
        public void unbind() {
            args.clear();
            ttBuilder.setLength(0);

            propTt.hide();
            selectMode.unbind();
            graph.setSelectFn(null);
        }

        private void updateForArgs() {
            final String param = PROP_PARAMS[args.size()];
            propTt.setText(String.format("%s<%s...>", ttBuilder, param.toLowerCase()));
            text.setPromptText(param);
        }
    };

    private javafx.scene.Node createToolbar() {
        final ToggleButton activate = new ToggleButton();
        initButton(activate, "res/lightning.png", "Activate");
        initButton(property, "res/details.png", "Create property");
        initButton(delete, "res/delete.png", "Delete");

        activate.setOnAction(e -> {
            if (activate.isSelected()) {
                setMode(activateMode);
                property.setSelected(false);

                if (graph.getSelected() != null) {
                    activate.setSelected(false);
                    setMode(defaultMode);
                }
            } else {
                setMode(defaultMode);
            }
        });

        property.setOnAction(e -> {
            if (property.isSelected()) {
                setMode(propertyMode);
                activate.setSelected(false);
            } else {
                setMode(defaultMode);
            }
        });

        graph.setOnMouseMoved(e -> {
            propTt.setX(e.getScreenX() + 32);
            propTt.setY(e.getScreenY() + 16);
        });

        graph.rxSelected().subscribe(opt -> {
            if (opt.isPresent()) {
                delete.setDisable(false);
                if (opt.get() instanceof Node) {
                    delete.setOnAction(e -> ((Node) opt.get()).delete());
                } else if (opt.get() instanceof Property) {
                    delete.setOnAction(e -> {
                        final Property prop = (Property) opt.get();
                        prop.object.setProperty(prop.property, Optional.empty());
                    });
                } else if (opt.get() instanceof Association) {
                    delete.setOnAction(e -> {
                        final Association assoc = (Association) opt.get();
                        assoc.dest.getSynapse().dissociate(assoc.source);
                    });
                } else {
                    delete.setOnAction(e -> System.out.println("Not implemented"));
                }
            } else {
                delete.setDisable(true);
            }
        });

        setMode(defaultMode);

        text.setOnAction(e -> selectNode(text.getText()));

        return new ToolBar(activate, property, delete, text);
    }

    private void addDetailRow(final GridPane parent, final javafx.scene.Node... cells) {
        final int row = parent.getChildren().isEmpty() ? 0
                : GridPane.getRowIndex(parent.getChildren().get(parent.getChildren().size() - 1)) + 1;
        parent.addRow(row, cells);
    }

    private void addDetail(final GridPane parent, final String label, final javafx.scene.Node value) {
        final StackPane labelPane = new StackPane(new Label(label)), valuePane = new StackPane(value);
        labelPane.setAlignment(Pos.BASELINE_RIGHT);
        valuePane.setAlignment(Pos.BASELINE_LEFT);
        addDetailRow(parent, labelPane, valuePane);
    }

    private void addDetailHeader(final GridPane parent, final String header) {
        final StackPane headerPane = new StackPane(new Label(header));
        GridPane.setColumnSpan(headerPane, 2);
        addDetailRow(parent, headerPane);
    }

    private void addDetail(final GridPane parent, final String label, final Observable<String> value,
            final Consumer<String> onChange) {
        final TextField field = new TextField();
        value.subscribe(v -> {
            if (!field.isFocused()) {
                field.setText(v);
            }
        });
        field.textProperty().addListener((o, a, b) -> {
            try {
                onChange.accept(b);
            } catch (final RuntimeException e) {
            }
        });
        addDetail(parent, label, field);
    }

    private Observable<String> wrapChangeObservable(final ChangeObservable<?> c, final Supplier<Object> getValue) {
        return c.rxChange().map(o -> Optional.ofNullable(getValue.get())).startWith(Optional.ofNullable(getValue.get()))
                .distinctUntilChanged().map(opt -> opt.map(Object::toString).orElse(""))
                .observeOn(JavaFxScheduler.platform());
    }

    private Observable<String> getNodeObservable(final Node node, final Function<Node, Object> getValue) {
        return wrapChangeObservable(node, () -> getValue.apply(node));
    }

    private void addDetail(final GridPane parent, final String label, final Node node,
            final Function<Node, Object> getValue) {
        final Label value = new Label();
        getNodeObservable(node, getValue).subscribe(value::setText);
        addDetail(parent, label, value);
    }

    private void addDetail(final GridPane parent, final String label, final Node node,
            final Function<Node, Object> getValue, final Consumer<String> setValue) {
        addDetail(parent, label, getNodeObservable(node, getValue), setValue);
    }

    private javafx.scene.Node createDetails() {
        final GridPane details = new GridPane();
        details.getStylesheets().add(getClass().getResource("details.css").toString());
        details.setPrefWidth(280);
        final ColumnConstraints c0 = new ColumnConstraints(), c1 = new ColumnConstraints();
        c0.setPrefWidth(80);
        c0.setHalignment(HPos.RIGHT);
        c1.setPrefWidth(200);
        details.getColumnConstraints().addAll(c0, c1);

        graph.rxSelected().subscribe(opt -> {
            details.getChildren().clear();

            if (opt.isPresent()) {
                final Object obj = opt.get();
                if (obj instanceof Node) {
                    final Node node = (Node) obj;
                    addDetail(details, "Value", node, Node::getValue);
                    addDetail(details, "Class", node,
                            n -> n.getValue() == null ? null : n.getValue().getClass().getName());
                    addDetail(details, "Comment", node, Node::getComment, node::setComment);
                    addDetail(details, "Refractory", node, Node::getRefractory,
                            v -> node.setRefractory(Long.parseLong(v)));

                    addDetailHeader(details, "Properties");
                    for (final Entry<Node, Node> prop : node.getProperties().entrySet()) {
                        addDetail(details, prop.getKey().displayString(), new Label(prop.getValue().displayString()));
                    }
                } else if (obj instanceof Association) {
                    final Association assoc = (Association) obj;
                    addDetail(details, "Source", new Label(assoc.source.displayString()));
                    addDetail(details, "Destination", new Label(assoc.dest.displayString()));
                    addDetail(details, "Coefficient",
                            wrapChangeObservable(assoc.dest.getSynapse(),
                                    () -> assoc.dest.getSynapse().getCoefficient(assoc.source)),
                            v -> assoc.dest.getSynapse().setCoefficient(assoc.source, Float.parseFloat(v)));
                    addDetail(details, "Decay",
                            wrapChangeObservable(assoc.dest.getSynapse(),
                                    () -> assoc.dest.getSynapse().getDecayPeriod(assoc.source)),
                            v -> assoc.dest.getSynapse().setDecayPeriod(assoc.source, Long.parseLong(v)));
                }
            } else {
                addDetailHeader(details, "Context");
                for (final Entry<Node, Node> prop : kb.node(Common.context).getProperties().entrySet()) {
                    addDetail(details, prop.getKey().displayString(), new Label(prop.getValue().displayString()));
                }
            }
        });

        return details;
    }

    private javafx.scene.Node createConsole() {
        final TextArea output = new TextArea();

        input = new TextField();
        input.setPromptText("Input");
        input.setOnAction(e -> {
            output.setText(String.format("%s%s\n", output.getText(), input.getText()));
            repl.sendInput(input.getText());
            input.clear();
        });

        output.setPromptText("Output");
        output.setEditable(false);
        repl.commandOutput().observeOn(JavaFxScheduler.platform())
                .subscribe(s -> output.setText(String.format("%s! %s\n", output.getText(), s)));
        repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS)).observeOn(JavaFxScheduler.platform())
                .subscribe(s -> output.setText(String.format("%s* %s\n", output.getText(), String.join("", s))));

        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                Platform.runLater(() -> {
                    if (output.getText().isEmpty() || output.getText().endsWith("\n"))
                        output.appendText("! ");
                    output.appendText(Character.toString((char) b));
                });
            }
        }));

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(input);
        borderPane.setCenter(output);
        return borderPane;
    }
}
