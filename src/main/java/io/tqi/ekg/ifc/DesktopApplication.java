package io.tqi.ekg.ifc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.concurrent.TimeUnit;

import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import io.tqi.ekg.ifc.fx.GraphPanel;
import javafx.application.Application;
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

        repl.commandOutput().subscribe(s -> System.out.format("! %s\n", s));
        repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS))
                .subscribe(s -> System.out.format("* %s\n", String.join("", s)));
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        primaryStage.setTitle("EKG");
        primaryStage.setScene(new GraphPanel(kb).createScene());
        primaryStage.setMaximized(true);
        primaryStage.show();
    }

}
