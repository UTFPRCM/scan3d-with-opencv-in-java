package scan3d.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import scan3d.Pipeline;
import scan3d.PlyReader;
import scan3d.ProgressListener;

/**
 * Janela principal: "Processar imagens" roda o {@link Pipeline}; "Visualizar" abre a nuvem de pontos e os quadros
 * de um processamento já feito. O OpenCV precisa ter sido carregado antes (feito em {@code Main}).
 */
public final class GuiApp extends Application {

    private final TextField inField = new TextField();
    private final TextField outField = new TextField();
    private final TextField cameraField = new TextField();
    private final CheckBox approxBox = new CheckBox("Câmera aproximada (sem calibração)");
    private final CheckBox threeBox = new CheckBox("Aceitar quadros com 3 marcadores (confiança menor)");
    private final Button processBtn = new Button("Processar imagens");
    private final Button viewBtn = new Button("Visualizar");
    private final Button openBtn = new Button("Abrir pasta de saída");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label phase = new Label("Pronto.");
    private final TextArea log = new TextArea();
    private final TabPane tabs = new TabPane();
    private final CloudView cloudView = new CloudView();
    private final FramesPane framesPane = new FramesPane();
    private final ContoursPane contoursPane = new ContoursPane();
    private Tab cloudTab, framesTab, contoursTab, logTab;
    private boolean running;
    private Runnable afterProcess = () -> {};
    private double[] initialView;                    // --view YAW,PITCH (graus), só para testes/capturas

    /** Ponto de entrada usado por {@code Main}. Opções: --in DIR, --out DIR; de teste: --load, --process, --screenshot ARQ, --tab N (0 Processamento, 1 Nuvem, 2 Quadros, 3 Contornos), --view YAW,PITCH (graus). */
    public static void open(String[] args) {
        Application.launch(GuiApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        List<String> raw = getParameters().getRaw();
        inField.setText(opt(raw, "--in", Files.isDirectory(Path.of("in/800x480 com objeto")) ? "in/800x480 com objeto" : ""));
        outField.setText(opt(raw, "--out", "output"));
        cameraField.setText("TextMatrix.txt");
        approxBox.setSelected(true);
        approxBox.setTooltip(new Tooltip("Usa f = largura da imagem e ponto principal no centro. Recomendado enquanto\n"
                + "não houver uma calibração feita na mesma resolução das fotos."));
        threeBox.setSelected(true);
        cameraField.disableProperty().bind(approxBox.selectedProperty());

        Button inBtn = new Button("Escolher...");
        inBtn.setOnAction(e -> chooseDir(stage, inField));
        Button outBtn = new Button("Escolher...");
        outBtn.setOnAction(e -> chooseDir(stage, outField));
        Button camBtn = new Button("Escolher...");
        camBtn.disableProperty().bind(approxBox.selectedProperty());
        camBtn.setOnAction(e -> {
            File f = new FileChooser().showOpenDialog(stage);
            if (f != null) cameraField.setText(f.getPath());
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.addRow(0, new Label("Pasta das fotos:"), inField, inBtn);
        form.addRow(1, new Label("Pasta de saída:"), outField, outBtn);
        form.addRow(2, new Label("Câmera (K):"), cameraField, camBtn);
        form.add(approxBox, 1, 3);
        form.add(threeBox, 1, 4);
        GridPane.setHgrow(inField, Priority.ALWAYS);
        GridPane.setHgrow(outField, Priority.ALWAYS);
        GridPane.setHgrow(cameraField, Priority.ALWAYS);

        processBtn.setDefaultButton(true);
        processBtn.setOnAction(e -> process(stage));
        viewBtn.setOnAction(e -> loadResults(true));
        openBtn.setOnAction(e -> getHostServices().showDocument(outDir().toAbsolutePath().toUri().toString()));
        HBox buttons = new HBox(10, processBtn, viewBtn, openBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        HBox progressRow = new HBox(10, phase, progress);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progress.setPrefWidth(300);

        VBox top = new VBox(10, form, buttons, progressRow);
        top.setPadding(new Insets(10));

        log.setEditable(false);
        log.setStyle("-fx-font-family: 'Menlo', 'Monaco', monospace; -fx-font-size: 12;");
        logTab = new Tab("Processamento", log);
        cloudTab = new Tab("Nuvem de pontos", cloudView);
        framesTab = new Tab("Quadros", framesPane);
        contoursTab = new Tab("Contornos", contoursPane);
        for (Tab t : List.of(logTab, cloudTab, framesTab, contoursTab)) t.setClosable(false);
        tabs.getTabs().addAll(logTab, cloudTab, framesTab, contoursTab);

        BorderPane root = new BorderPane(tabs);
        root.setTop(top);
        stage.setTitle("scan3d — reconstrução 3D com marcador planar");
        stage.setScene(new Scene(root, 1100, 760));
        stage.show();

        outField.textProperty().addListener((o, a, b) -> refreshButtons());
        refreshButtons();
        appendLog("Escolha a pasta com as fotos e clique em \"Processar imagens\". "
                + "Se a saída já existe, \"Visualizar\" abre a nuvem e os quadros.");

        String view = opt(raw, "--view", null);
        if (view != null) {
            String[] v = view.split(",");
            initialView = new double[] {Double.parseDouble(v[0]), Double.parseDouble(v[1])};
        }
        boolean load = raw.contains("--load");
        if (load && !viewBtn.isDisabled()) loadResults(!raw.contains("--screenshot"));
        String shot = opt(raw, "--screenshot", null);
        int shotTab = Integer.parseInt(opt(raw, "--tab", "1"));
        if (raw.contains("--process")) { // modo de teste: dispara o mesmo caminho do botão "Processar imagens"
            if (shot != null) afterProcess = () -> screenshotThenClose(stage, shot, shotTab, 3.0);
            process(stage);
        } else if (shot != null) {
            screenshotThenClose(stage, shot, shotTab, 2.5);
        }
    }

    /** Modo de teste/documentação: espera a tela assentar, grava um PNG da janela e fecha. */
    private void screenshotThenClose(Stage stage, String file, int tab, double seconds) {
        PauseTransition wait = new PauseTransition(Duration.seconds(seconds));
        wait.setOnFinished(e -> {
            tabs.getSelectionModel().select(tab); // por último: a carga dos resultados também troca de aba
            Platform.runLater(() -> {
                try {
                    savePng(stage.getScene().getRoot().snapshot(null, null), Path.of(file));
                    System.out.println("Captura de tela gravada em " + file);
                } catch (IOException ex) {
                    System.err.println("Falha ao gravar captura: " + ex.getMessage());
                }
                stage.close(); // mesmo caminho de quando o usuário fecha a janela
            });
        });
        wait.play();
    }

    // ------------------------------------------------------------------ ações

    private void process(Stage stage) {
        if (running) return;
        Path in = Path.of(inField.getText().trim());
        Path out = outDir();
        if (inField.getText().isBlank() || !Files.isDirectory(in)) {
            alert(Alert.AlertType.WARNING, "Pasta das fotos inválida", "Escolha uma pasta que exista e contenha fotos .jpg, .jpeg ou .png.");
            return;
        }
        if (!approxBox.isSelected() && !Files.isRegularFile(Path.of(cameraField.getText().trim()))) {
            alert(Alert.AlertType.WARNING, "Arquivo da câmera não encontrado",
                    "Informe um arquivo de matriz intrínseca ou marque \"Câmera aproximada\".");
            return;
        }
        if (Files.exists(out.resolve("cloud.ply"))) {
            var answer = new Alert(Alert.AlertType.CONFIRMATION,
                    "Já existe um processamento em:\n" + out.toAbsolutePath() + "\n\nProcessar de novo substitui os arquivos dele.",
                    ButtonType.OK, ButtonType.CANCEL).showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
        }

        Pipeline.Config cfg = new Pipeline.Config(in, out, Path.of(cameraField.getText().trim()), approxBox.isSelected(), 0,
                threeBox.isSelected() ? 3 : 4, Pipeline.Config.DEFAULT_MAX_GAP);
        log.clear();
        appendLog("Processando " + in.toAbsolutePath() + "\nSaída: " + out.toAbsolutePath() + "\n");
        setRunning(true);
        tabs.getSelectionModel().select(logTab);

        Task<Pipeline.Summary> task = new Task<>() {
            @Override
            protected Pipeline.Summary call() throws Exception {
                return new Pipeline(cfg).run(new ProgressListener() {
                    @Override
                    public void progress(String ph, int done, int total) {
                        Platform.runLater(() -> {
                            phase.setText(ph);
                            progress.setProgress(total <= 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) done / total);
                        });
                    }

                    @Override
                    public void log(String message) {
                        Platform.runLater(() -> appendLog(message));
                    }
                });
            }
        };
        task.setOnSucceeded(e -> {
            Pipeline.Summary s = task.getValue();
            appendLog(String.format("%n%d de %d quadros usados: %d com 4 marcadores e %d com 3 (confiança menor).%n%d ignorados (lista em skipped.txt).",
                    s.used(), s.total(), s.used4(), s.used3(), s.skipped().size()));
            phase.setText("Concluído.");
            progress.setProgress(1);
            setRunning(false);
            loadResults(true);
            afterProcess.run();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            appendLog("\nERRO: " + ex);
            phase.setText("Falhou.");
            progress.setProgress(0);
            setRunning(false);
            alert(Alert.AlertType.ERROR, "O processamento falhou", String.valueOf(ex.getMessage()));
        });
        Thread t = new Thread(task, "scan3d-pipeline");
        t.setDaemon(true);
        t.start();
    }

    /** Carrega nuvem e quadros da pasta de saída; {@code switchTab} leva o usuário à aba da nuvem. */
    private void loadResults(boolean switchTab) {
        Path out = outDir();
        Path in = inField.getText().isBlank() ? null : Path.of(inField.getText().trim());
        Task<PlyReader.Cloud> task = new Task<>() {
            @Override
            protected PlyReader.Cloud call() throws Exception {
                return PlyReader.read(out.resolve("cloud.ply"));
            }
        };
        phase.setText("Carregando resultados...");
        task.setOnSucceeded(e -> {
            cloudView.setCloud(task.getValue());
            if (initialView != null) cloudView.setView(initialView[0], initialView[1]);
            try {
                framesPane.load(out, in);
                contoursPane.load(out, in);
            } catch (IOException | RuntimeException ex) {
                appendLog("Não foi possível ler os quadros: " + ex);
            }
            phase.setText("Resultados carregados de " + out);
            if (switchTab) tabs.getSelectionModel().select(cloudTab);
        });
        task.setOnFailed(e -> {
            phase.setText("Falhou.");
            alert(Alert.AlertType.ERROR, "Não foi possível abrir a nuvem", String.valueOf(task.getException().getMessage()));
        });
        Thread t = new Thread(task, "scan3d-load");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ utilidades

    private Path outDir() {
        return Path.of(outField.getText().trim().isEmpty() ? "output" : outField.getText().trim());
    }

    private void refreshButtons() {
        boolean has = Files.exists(outDir().resolve("cloud.ply")) && !running;
        viewBtn.setDisable(!has);
        openBtn.setDisable(!Files.isDirectory(outDir()));
        viewBtn.setTooltip(new Tooltip(has ? "Abre a nuvem de pontos e os quadros de " + outDir()
                : "Ainda não há resultado em " + outDir() + ". Processe as imagens primeiro."));
    }

    private void setRunning(boolean r) {
        running = r;
        processBtn.setDisable(r);
        inField.setDisable(r);
        outField.setDisable(r);
        approxBox.setDisable(r);
        threeBox.setDisable(r);
        refreshButtons();
    }

    private void appendLog(String s) {
        log.appendText(s + "\n");
    }

    private void chooseDir(Stage stage, TextField target) {
        DirectoryChooser c = new DirectoryChooser();
        Path cur = Path.of(target.getText().isBlank() ? "." : target.getText().trim());
        if (Files.isDirectory(cur)) c.setInitialDirectory(cur.toAbsolutePath().toFile());
        File f = c.showDialog(stage);
        if (f != null) target.setText(f.getPath());
    }

    private static void alert(Alert.AlertType type, String header, String text) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setHeaderText(header);
        a.showAndWait();
    }

    private static String opt(List<String> raw, String key, String def) {
        int i = raw.indexOf(key);
        return i >= 0 && i + 1 < raw.size() ? raw.get(i + 1) : def;
    }

    /** Grava um PNG sem depender de javafx-swing. */
    private static void savePng(WritableImage img, Path file) throws IOException {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        PixelReader r = img.getPixelReader();
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) bi.setRGB(x, y, r.getArgb(x, y));
        }
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
    }
}
