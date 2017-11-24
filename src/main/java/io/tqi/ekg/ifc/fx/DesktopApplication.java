package io.tqi.ekg.ifc.fx;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.Disposable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
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

        final Node console = createConsole();
        final SplitPane splitPane = new SplitPane(graph, console);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(.5);
        SplitPane.setResizableWithParent(console, false);

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(createToolbar());
        borderPane.setCenter(splitPane);
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

    private io.tqi.ekg.Node findNode(final String query) {
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
                if (selection.get() instanceof io.tqi.ekg.Node) {
                    final io.tqi.ekg.Node node = (io.tqi.ekg.Node) selection.get();
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
                if (o instanceof io.tqi.ekg.Node) {
                    ((io.tqi.ekg.Node) o).activate();
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
        final List<io.tqi.ekg.Node> args = new ArrayList<>();
        final StringBuilder ttBuilder = new StringBuilder();

        @Override
        public void bind() {
            selectMode.bind();
            updateForArgs();

            graph.setSelectFn(o -> {
                if (o instanceof io.tqi.ekg.Node) {
                    final io.tqi.ekg.Node node = (io.tqi.ekg.Node) o;
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

    private Node createToolbar() {
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
                if (opt.get() instanceof io.tqi.ekg.Node) {
                    delete.setOnAction(e -> ((io.tqi.ekg.Node) opt.get()).delete());
                } else if (opt.get() instanceof GraphPanel.Property) {
                    delete.setOnAction(e -> {
                        final GraphPanel.Property prop = (GraphPanel.Property) opt.get();
                        prop.object.setProperty(prop.property, Optional.empty());
                    });
                } else if (opt.get() instanceof GraphPanel.Association) {
                    delete.setOnAction(e -> {
                        final GraphPanel.Association assoc = (GraphPanel.Association) opt.get();
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

    private Node createConsole() {
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
                if (output.getText().isEmpty() || output.getText().endsWith("\n"))
                    output.appendText("! ");
                output.appendText(Character.toString((char) b));
            }
        }));

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(input);
        borderPane.setCenter(output);
        return borderPane;
    }
}
