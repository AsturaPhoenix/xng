package io.tqi.ekg.ifc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import io.tqi.ekg.ifc.fx.GraphPanel;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class DesktopApplication extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    final KnowledgeBase kb;
    final Repl repl;

    public DesktopApplication() throws FileNotFoundException, ClassNotFoundException, ClassCastException, IOException {
        this.kb = SerializingPersistence.loadBound(FileSystems.getDefault().getPath("persistence"));

        repl = new Repl(kb);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        primaryStage.setTitle("EKG");

        final BorderPane borderPane = new BorderPane();
        borderPane.setCenter(new GraphPanel(kb));
        borderPane.setBottom(createConsole());

        final Scene scene = new Scene(borderPane);
        primaryStage.setScene(scene);

        primaryStage.setMaximized(true);
        primaryStage.show();

        primaryStage.setOnCloseRequest(e -> kb.close());
    }

    private Node createConsole() {
        final TextArea output = new TextArea();

        final TextField input = new TextField();
        input.setPromptText("input");
        input.setOnAction(e -> {
            output.setText(String.format("%s%s\n", output.getText(), input.getText()));
            repl.sendInput(input.getText());
            input.clear();
        });

        output.setPromptText("no output");
        output.setEditable(false);
        repl.commandOutput().observeOn(JavaFxScheduler.platform())
                .subscribe(s -> output.setText(String.format("%s! %s\n", output.getText(), s)));
        repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS)).observeOn(JavaFxScheduler.platform())
                .subscribe(s -> output.setText(String.format("%s* %s\n", output.getText(), String.join("", s))));

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(input);
        borderPane.setCenter(output);
        return borderPane;
    }
}
