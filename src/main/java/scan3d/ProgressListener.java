package scan3d;

/** Recebe o andamento do {@link Pipeline}. Chamado na thread que executa o pipeline. */
public interface ProgressListener {

    /** @param phase descrição da fase atual; @param done quantos itens concluídos; @param total total de itens */
    void progress(String phase, int done, int total);

    /** Mensagem informativa (avisos, quadros ignorados etc.). */
    void log(String message);

    ProgressListener NONE = new ProgressListener() {
        @Override
        public void progress(String phase, int done, int total) {}

        @Override
        public void log(String message) {}
    };
}
