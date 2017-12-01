package io.tqi.ekg.ifc.fx;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.tqi.ekg.ChangeObservable;
import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.KnowledgeBase.Common;
import io.tqi.ekg.Node;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import io.tqi.ekg.Synapse;
import io.tqi.ekg.ifc.fx.GraphPanel.Association;
import io.tqi.ekg.ifc.fx.GraphPanel.Property;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.MapChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
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
        splitPane.setDividerPositions(.8);
        SplitPane.setResizableWithParent(console, false);

        final ScrollPane details = new ScrollPane(createDetails());
        details.setHbarPolicy(ScrollBarPolicy.NEVER);
        details.setHmax(0);

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(createToolbar());
        borderPane.setCenter(splitPane);
        borderPane.setRight(details);
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
                        args.get(0).properties().put(args.get(1), args.get(2));

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
                        prop.object.properties().remove(prop.property);
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

    private final CompositeDisposable detailDisposables = new CompositeDisposable();

    private static javafx.scene.Node[] formatRow(final javafx.scene.Node... cells) {
        final javafx.scene.Node[] formatted = new javafx.scene.Node[cells.length];
        for (int i = 0; i < cells.length; i++) {
            final javafx.scene.Node content = cells[i];
            final StackPane cell = new StackPane(content);
            if (cells.length == 1) {
                cell.setAlignment(Pos.BASELINE_CENTER);
                GridPane.setColumnSpan(cell, 2);
            } else {
                cell.setAlignment(i == 0 ? Pos.BASELINE_RIGHT : Pos.BASELINE_LEFT);
            }
            cell.setOnMouseClicked(e -> {
                final EventHandler<? super MouseEvent> handler = content.getOnMouseClicked();
                if (handler != null)
                    handler.handle(e);
            });
            formatted[i] = cell;
        }
        return formatted;
    }

    private static javafx.scene.Node unwrapCell(final GridPane parent, final int i) {
        return unwrapCell(parent.getChildren().get(i));
    }

    private static javafx.scene.Node unwrapCell(final javafx.scene.Node cell) {
        return ((StackPane) cell).getChildren().get(0);
    }

    private static void addDetailRow(final GridPane parent, final javafx.scene.Node... cells) {
        final int row = parent.getChildren().isEmpty() ? 0
                : GridPane.getRowIndex(parent.getChildren().get(parent.getChildren().size() - 1)) + 1;
        parent.addRow(row, cells);
    }

    private static void insertDetailRow(final GridPane parent, final int index, final javafx.scene.Node... cells) {
        final int row = index == 0 ? 0 : GridPane.getRowIndex(parent.getChildren().get(index - 1)) + 1;
        parent.getChildren().addAll(index, Arrays.asList(cells));
        for (int i = 0; i < cells.length; i++) {
            GridPane.setConstraints(cells[i], i, row);
        }
        for (int i = index + cells.length; i < parent.getChildren().size(); i++) {
            final javafx.scene.Node cell = parent.getChildren().get(i);
            GridPane.setRowIndex(cell, GridPane.getRowIndex(cell) + 1);
        }
    }

    private static StackPane addDetailHeader(final GridPane parent, final String header) {
        final StackPane headerPane = new StackPane(new Label(header));
        GridPane.setColumnSpan(headerPane, 2);
        headerPane.getStyleClass().add("header");
        addDetailRow(parent, headerPane);
        return headerPane;
    }

    private class DynamicLabel extends Label implements Disposable {
        final Tooltip tooltip = new Tooltip();
        Disposable subscription;

        DynamicLabel() {
            setTooltip(tooltip);
            getStyleClass().add("label");
        }

        @Override
        public void dispose() {
            if (subscription != null) {
                subscription.dispose();
            }
        }

        @Override
        public boolean isDisposed() {
            return subscription == null || subscription.isDisposed();
        }

        DynamicLabel setSource(final Observable<String> source) {
            dispose();
            subscription = source.subscribe(s -> {
                setText(s);
                tooltip.setText(s);
            });
            return this;
        }

        DynamicLabel setSource(final Node node) {
            setSource(getNodeObservable(node));
            setOnMouseClicked(e -> graph.select(node));
            return this;
        }
    }

    private static class DynamicTextField extends TextField implements Disposable {
        Disposable subscription;
        ChangeListener<String> changeListener;

        @Override
        public void dispose() {
            if (subscription != null) {
                subscription.dispose();
            }
            if (changeListener != null) {
                textProperty().removeListener(changeListener);
            }
        }

        @Override
        public boolean isDisposed() {
            return subscription == null || subscription.isDisposed();
        }

        DynamicTextField setSource(final Observable<String> source, final Consumer<String> onChange) {
            dispose();
            source.subscribe(v -> {
                if (!isFocused()) {
                    setText(v);
                }
            });
            changeListener = (o, a, b) -> {
                try {
                    onChange.accept(b);
                } catch (final RuntimeException e) {
                }
            };
            textProperty().addListener(changeListener);
            return this;
        }
    }

    private static void addDetail(final GridPane parent, final String label, final javafx.scene.Node value) {
        addDetailRow(parent, formatRow(new Label(label), value));
    }

    private static void addDetail(final GridPane parent, final String label, final Observable<String> value,
            final Consumer<String> onChange) {
        addDetail(parent, label, new DynamicTextField().setSource(value, onChange));
    }

    private static Observable<String> wrapChangeObservable(final ChangeObservable<?> c,
            final Supplier<Object> getValue) {
        return c.rxChange().map(o -> Optional.ofNullable(getValue.get())).startWith(Optional.ofNullable(getValue.get()))
                .distinctUntilChanged().map(opt -> opt.map(v -> {
                    if (v instanceof Throwable) {
                        return Throwables.getStackTraceAsString((Throwable) v);
                    } else {
                        return v.toString();
                    }
                }).orElse("")).observeOn(JavaFxScheduler.platform());
    }

    private static Observable<String> getNodeObservable(final Node node) {
        return getNodeObservable(node, Node::displayString);
    }

    private static Observable<String> getNodeObservable(final Node node, final Function<Node, Object> getValue) {
        return wrapChangeObservable(node, () -> getValue.apply(node));
    }

    private DynamicLabel createNodeLabel(final Node node) {
        return new DynamicLabel().setSource(node);
    }

    private void addDetail(final GridPane parent, final String label, final Node node,
            final Function<Node, Object> getValue) {
        addDetail(parent, label, new DynamicLabel().setSource(getNodeObservable(node, getValue)));
    }

    private static void addDetail(final GridPane parent, final String label, final Node node,
            final Function<Node, Object> getValue, final Consumer<String> setValue) {
        addDetail(parent, label, getNodeObservable(node, getValue), setValue);
    }

    private static final int HISTORY = Synapse.EVALUATION_HISTORY;

    private long baseline;

    private String formatTime(final long time) {
        return baseline - time + " ms";
    }

    private static <T> List<T> addHistoryElement(final List<T> list, final T element) {
        if (list.size() >= HISTORY) {
            list.subList(HISTORY - 1, list.size()).clear();
        }
        list.add(0, element);
        return list;
    }

    private static <T> Observable<List<T>> accumulateHistory(final Observable<T> source) {
        return source.observeOn(JavaFxScheduler.platform()).scan(new ArrayList<>(),
                DesktopApplication::addHistoryElement);
    }

    private <T> void addHistoryTable(final GridPane parent, final String header, final Observable<T> source,
            final Supplier<javafx.scene.Node[]> initRow, final BiConsumer<T, javafx.scene.Node[]> updateRow) {
        final StackPane headerPane = addDetailHeader(parent, header);
        final int[] n = new int[2];

        detailDisposables.add(accumulateHistory(source).subscribe(h -> {
            final int i0 = parent.getChildren().indexOf(headerPane) + 1;
            int i = 0;
            for (final T e : h) {
                final javafx.scene.Node[] row;
                if (i < h.size() - 1 || n[1] >= HISTORY) {
                    row = new javafx.scene.Node[n[0]];
                    for (int j = 0; j < n[0]; j++) {
                        row[j] = unwrapCell(parent, i0 + n[0] * i + j);
                    }
                } else {
                    row = initRow.get();
                    n[0] = row.length;
                    insertDetailRow(parent, i0 + n[0] * i, formatRow(row));
                    n[1]++;
                }
                updateRow.accept(e, row);
                i++;
            }
        }));
    }

    private void addEvaluationHistory(final GridPane parent, final Node node) {
        addHistoryTable(parent, "Synapse history", node.getSynapse().rxValue(),
                () -> new javafx.scene.Node[] { new Label(), new Label() }, (e, cells) -> {
                    ((Label) cells[0]).setText(formatTime(e.time));
                    ((Label) cells[1]).setText(Float.toString(e.value));
                });
    }

    private void addNodeActivationHistory(final GridPane parent, final Node node) {
        addHistoryTable(parent, "Activation history", node.rxActivationHistory(),
                () -> new javafx.scene.Node[] { new Label() }, (t, cells) -> {
                    ((Label) cells[0]).setText(formatTime(t));
                });
    }

    private int activationHistoryBegin, activationHistoryLength;

    private Observable<String> getActivationObservable(final Node node) {
        return getNodeObservable(node, n -> formatTime(n.getLastActivation()));
    }

    private javafx.scene.Node[] createActivationRow(final Node node) {
        return formatRow(createNodeLabel(node), new DynamicLabel().setSource(getActivationObservable(node)));
    }

    private void addActivationHistory(final GridPane parent, final int maxLength) {
        addDetailHeader(parent, "Activations");
        activationHistoryBegin = parent.getChildren().size();
        activationHistoryLength = 0;

        synchronized (kb.iteratorMutex()) {
            for (final Node node : kb) {
                if (activationHistoryLength >= maxLength)
                    break;
                activationHistoryLength++;

                if (baseline == Long.MIN_VALUE) {
                    baseline = node.getLastActivation();
                }
                addDetailRow(parent, createActivationRow(node));
            }
        }

        detailDisposables.add(kb.rxActivate().toFlowable(BackpressureStrategy.DROP)
                .observeOn(JavaFxScheduler.platform()).subscribe(n -> {
                    int i = 0;
                    synchronized (kb.iteratorMutex()) {
                        for (final Node node : kb) {
                            if (i >= maxLength)
                                break;

                            if (i < activationHistoryLength) {
                                ((DynamicLabel) unwrapCell(parent, activationHistoryBegin + 2 * i)).setSource(node);
                                ((DynamicLabel) unwrapCell(parent, activationHistoryBegin + 2 * i + 1))
                                        .setSource(getActivationObservable(node));
                            } else {
                                insertDetailRow(parent, activationHistoryBegin + 2 * i, createActivationRow(node));
                            }

                            i++;
                        }
                    }

                    if (activationHistoryLength > i) {
                        final List<javafx.scene.Node> toRemove = parent.getChildren().subList(
                                activationHistoryBegin + 2 * i, activationHistoryBegin + 2 * activationHistoryLength);
                        toRemove.forEach(DesktopApplication::disposeCell);
                        toRemove.clear();
                    }

                    activationHistoryLength = i;
                }));
    }

    private Runnable disposePropertiesListener;

    private void addProperties(final GridPane parent, final String header, final Node node) {
        addDetailHeader(parent, header);

        synchronized (node.properties().mutex()) {
            final int propsStart = parent.getChildren().size();

            for (final Entry<Node, Node> prop : Lists.reverse(new ArrayList<>(node.properties().entrySet()))) {
                addDetailRow(parent, formatRow(createNodeLabel(prop.getKey()), createNodeLabel(prop.getValue())));
            }

            MapChangeListener<Node, Node> listener = c -> Platform.runLater(() -> {
                synchronized (node.properties().mutex()) {
                    int i = propsStart;
                    for (final Entry<Node, Node> prop : Lists.reverse(new ArrayList<>(node.properties().entrySet()))) {
                        if (i < parent.getChildren().size()) {
                            ((DynamicLabel) unwrapCell(parent, i)).setSource(prop.getKey());
                            ((DynamicLabel) unwrapCell(parent, i + 1)).setSource(prop.getValue());
                        } else {
                            addDetailRow(parent,
                                    formatRow(createNodeLabel(prop.getKey()), createNodeLabel(prop.getValue())));
                        }
                        i += 2;
                    }
                    final List<javafx.scene.Node> toRemove = parent.getChildren().subList(i,
                            parent.getChildren().size());
                    toRemove.forEach(DesktopApplication::disposeCell);
                    toRemove.clear();
                }
            });

            node.properties().addListener(listener);
            disposePropertiesListener = () -> node.properties().removeListener(listener);
        }
    }

    private static void disposeCell(final javafx.scene.Node cell) {
        final javafx.scene.Node content = unwrapCell(cell);
        if (content instanceof Disposable) {
            ((Disposable) content).dispose();
        }
    }

    private javafx.scene.Node createDetails() {
        synchronized (kb.iteratorMutex()) {
            final Iterator<Node> init = kb.iterator();
            baseline = init.hasNext() ? init.next().getLastActivation() : System.currentTimeMillis();
            kb.rxActivate().subscribe(n -> baseline = n.getLastActivation());
        }

        final GridPane details = new GridPane();
        details.getStylesheets().add(getClass().getResource("details.css").toString());
        details.setPrefWidth(280);
        final ColumnConstraints c0 = new ColumnConstraints(), c1 = new ColumnConstraints();
        c0.setPrefWidth(140);
        c0.setHalignment(HPos.RIGHT);
        c1.setPrefWidth(140);
        details.getColumnConstraints().addAll(c0, c1);

        graph.rxSelected().distinctUntilChanged().subscribe(opt -> {
            if (disposePropertiesListener != null) {
                disposePropertiesListener.run();
                disposePropertiesListener = null;
            }
            detailDisposables.clear();
            details.getChildren().forEach(DesktopApplication::disposeCell);
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

                    addNodeActivationHistory(details, node);
                    addEvaluationHistory(details, node);

                    addProperties(details, "Properties", node);
                } else if (obj instanceof Association) {
                    final Association assoc = (Association) obj;
                    addDetail(details, "Source", createNodeLabel(assoc.source));
                    addDetail(details, "Destination", createNodeLabel(assoc.dest));
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
                addActivationHistory(details, 15);
                addProperties(details, "Context", kb.node(Common.context));
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
