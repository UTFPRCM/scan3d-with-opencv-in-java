package scan3d;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.opencv.core.Size;

public final class Main {

    private static final String USAGE = """
            Uso:
              scan      [--in DIR] [--out DIR] [--camera ARQUIVO] [--approx-camera] [--limit N] [--min-markers 3|4] [--max-gap N]
              calibrate --in DIR [--pattern 8x6] [--square 50] [--out ARQUIVO]
              gui       [--in DIR] [--out DIR]     abre a janela (processar imagens e visualizar a nuvem)

            scan       processa as fotos de DIR (padrão: "in/800x480 com objeto") e grava em DIR de saída (padrão: output).
                       --camera         arquivo com a matriz intrínseca (padrão: TextMatrix.txt)
                       --approx-camera  ignora o arquivo e usa K aproximada a partir do tamanho da imagem
                       --limit N        processa só as N primeiras fotos
                       --min-markers    3 (padrão) também aceita quadros com 3 marcadores, marcados como confiança menor;
                                        4 usa só quadros completos
                       --max-gap N      distância máxima, em quadros, até o quadro de 4 marcadores usado como referência (padrão 3)
            calibrate  calcula K com fotos de um tabuleiro de xadrez (cantos internos --pattern, lado do quadrado em mm)
                       e grava no formato de TextMatrix.txt (padrão: TextMatrix.txt)
            """;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (java.nio.file.NoSuchFileException e) {
            System.err.println("Erro: arquivo ou pasta não encontrado: " + e.getMessage());
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        String command = args[0].startsWith("--") ? "scan" : args[0];
        Map<String, String> opt = parse(args[0].startsWith("--") ? args : java.util.Arrays.copyOfRange(args, 1, args.length));

        nu.pattern.OpenCV.loadLocally();

        switch (command) {
            case "scan" -> scan(opt);
            case "calibrate" -> calibrate(opt);
            case "gui" -> scan3d.gui.GuiApp.open(args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            default -> {
                System.err.println("Comando desconhecido: " + command);
                System.err.print(USAGE);
                System.exit(2);
            }
        }
    }

    private static void scan(Map<String, String> opt) throws IOException {
        int minMarkers = Integer.parseInt(opt.getOrDefault("min-markers", "3"));
        if (minMarkers != 3 && minMarkers != 4) throw new IllegalArgumentException("--min-markers deve ser 3 ou 4");
        Pipeline.Config cfg = new Pipeline.Config(
                Path.of(opt.getOrDefault("in", "in/800x480 com objeto")),
                Path.of(opt.getOrDefault("out", "output")),
                Path.of(opt.getOrDefault("camera", "TextMatrix.txt")),
                opt.containsKey("approx-camera"),
                Integer.parseInt(opt.getOrDefault("limit", "0")),
                minMarkers,
                Integer.parseInt(opt.getOrDefault("max-gap", String.valueOf(Pipeline.Config.DEFAULT_MAX_GAP))));
        Pipeline.Summary s = new Pipeline(cfg).run(new ProgressListener() {
            @Override
            public void progress(String phase, int done, int total) {}

            @Override
            public void log(String message) {
                if (!message.startsWith("Ignorado")) System.out.println(message);
            }
        });
        System.out.printf("%d de %d quadros usados (%d com 4 marcadores, %d com 3 = confiança menor). Saída em %s%n",
                s.used(), s.total(), s.used4(), s.used3(), cfg.output().toAbsolutePath());
        if (!s.skipped().isEmpty()) {
            System.out.println("Quadros ignorados (" + s.skipped().size() + "):");
            s.skipped().forEach(m -> System.out.println("  " + m));
        }
    }

    private static void calibrate(Map<String, String> opt) throws IOException {
        if (!opt.containsKey("in")) {
            System.err.print(USAGE);
            System.exit(2);
        }
        String[] wh = opt.getOrDefault("pattern", "8x6").split("x");
        Calibrator c = new Calibrator(new Size(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])),
                Double.parseDouble(opt.getOrDefault("square", "50")));
        CameraMatrix k = c.calibrate(Path.of(opt.get("in")));
        Path out = Path.of(opt.getOrDefault("out", "TextMatrix.txt"));
        k.save(out);
        System.out.println("Matriz intrínseca gravada em " + out.toAbsolutePath());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            m.put(key, flag ? "true" : args[++i]);
        }
        return m;
    }
}
