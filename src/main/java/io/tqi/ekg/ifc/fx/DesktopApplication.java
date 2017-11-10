package io.tqi.ekg.ifc.fx;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
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

    private ChangeListener<String> tbTextListener;

    private void initButton(final ButtonBase button, final String res, final String altText) {
        try {
            button.setGraphic(new ImageView(new Image(new FileInputStream(res))));
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            button.setText(altText);
        }
    }

    private Disposable selectAction;

    private Node createToolbar() {
        final ToggleButton activate = new ToggleButton();
        initButton(activate, "res/lightning.png", "Activate");
        final ToggleButton property = new ToggleButton();
        initButton(property, "res/details.png", "Property");

        activate.setOnAction(e -> {
            if (selectAction != null) {
                selectAction.dispose();
                selectAction = null;
            }
            property.setSelected(false);

            if (activate.isSelected()) {
                graph.setSelectFn(o -> {
                    if (o instanceof io.tqi.ekg.Node) {
                        ((io.tqi.ekg.Node) o).activate();
                        return true;
                    } else {
                        return false;
                    }
                });
                if (graph.getSelected() != null) {
                    activate.setSelected(false);
                    graph.setSelectFn(null);
                }
            } else {
                graph.setSelectFn(null);
            }
        });

        property.setOnAction(e -> {
            if (selectAction != null) {
                selectAction.dispose();
                selectAction = null;
            }
            activate.setSelected(false);

            if (property.isSelected()) {
                final List<io.tqi.ekg.Node> args = new ArrayList<>();
                graph.setSelectFn(o -> {
                    if (o instanceof io.tqi.ekg.Node) {
                        args.add((io.tqi.ekg.Node) o);

                        if (args.size() == 3) {
                            args.get(0).setProperty(args.get(1), args.get(2));

                            property.setSelected(false);
                            graph.setSelectFn(null);
                        }

                        return true;
                    } else {
                        return false;
                    }
                });
            } else {
                graph.setSelectFn(null);
            }
        });

        final TextField text = new TextField();
        graph.rxSelected().subscribe(opt -> {
            if (tbTextListener != null)
                text.textProperty().removeListener(tbTextListener);
            if (!opt.isPresent()) {
                text.setPromptText("Find node");
                text.clear();
            } else if (opt.get() instanceof io.tqi.ekg.Node) {
                final io.tqi.ekg.Node node = (io.tqi.ekg.Node) opt.get();
                text.setPromptText("Comment");
                text.setText(node.getComment());
                text.positionCaret(text.getLength());

                tbTextListener = (o, a, b) -> {
                    node.setComment(b);
                };
                text.textProperty().addListener(tbTextListener);
            }
        });
        return new ToolBar(activate, property, text);
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