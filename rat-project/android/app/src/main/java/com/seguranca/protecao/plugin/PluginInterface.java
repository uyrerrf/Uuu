package com.seguranca.protecao.plugin;

import android.content.Context;
import android.os.Bundle;

/**
 * Contrato que todo plugin DEX deve implementar.
 * Plugins são carregados dinamicamente via DexClassLoader e devem
 * fornecer uma classe que implemente esta interface.
 */
public interface PluginInterface {

    /**
     * Identificador único do plugin.
     */
    String getPluginId();

    /**
     * Versão do plugin (semver string).
     */
    String getPluginVersion();

    /**
     * Chamado quando o plugin é carregado com sucesso.
     * @param context Contexto da aplicação host
     * @param config Configurações iniciais do plugin
     */
    void onLoad(Context context, Bundle config);

    /**
     * Executa uma ação do plugin.
     * @param action Nome da ação a executar
     * @param params Parâmetros da ação
     * @return Resultado da execução (tipo depende do plugin)
     */
    Object execute(String action, Bundle params);

    /**
     * Chamado antes de descarregar o plugin.
     * Deve liberar recursos e limpar estado.
     */
    void onUnload();

    /**
     * Verifica se o plugin é compatível com a versão do host.
     * @param hostVersion Versão do app host
     * @return true se compatível
     */
    boolean isCompatible(int hostVersion);
}
