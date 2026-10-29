# PLANO (uma etapa por sessão; /clear entre etapas; commit ao fim de cada uma)

## Etapa 0: Ambiente e fork (feito principalmente por mim, com ajuda do Claude)
- [ ] Fork de github.com/FossifyOrg/Gallery para a conta do GitHub; clonar na pasta do projeto.
- [ ] JDK 17 + Android SDK (Android Studio ou ferramentas de linha de comando) no desktop.
- [ ] Ativar depuração por Wi-Fi no S23; conectar com `adb`.
- [ ] Compilar o fork sem mudanças e instalar no celular.

## Etapa 1: Identidade do app
- [x] Trocar applicationId para `com.lucca.galeria` e o nome do app, para coexistir com o Fossify original.
- [x] Compilar, instalar, confirmar que abre e lista a galeria.

## Etapa 1.5: Abrir vídeos no player da Samsung
- [x] Descobrir o pacote do player da Samsung no S23 (`com.samsung.android.video`).
- [x] Ao tocar num vídeo (pasta e busca), abrir via ACTION_VIEW com URI content://, mime e setPackage; cair no comportamento padrão se falhar.
- [x] Opção em Configurações "Abrir vídeos no player da Samsung", ligada por padrão.
- [x] Testar no S23.

## Etapa 2: Ordenar por duração
- [x] Localizar onde o Fossify define as opções de ordenação dentro de pasta.
- [x] Adicionar "Duração" (crescente/decrescente), usando a duração do MediaStore/cache do Fossify.
- [x] Testar numa pasta só com vídeos e numa pasta mista.

## Etapa 3: Compressão de UM vídeo (sem fila)
- [x] Adicionar dependência Media3 Transformer (transformer, effect e common, na versão do exoplayer).
- [x] Criar um "analisador de vídeo" que lê resolução, codec, bitrate, fps, rotação, trilhas de áudio/legenda e HDR do arquivo (MediaExtractor/MediaMetadataRetriever). Será usado em todas as etapas seguintes.
- [x] Testar com uma amostra variada de vídeos baixados. Exceção: áudio 5.1 sem amostra (ver Etapa 9).
- [x] Ação "Comprimir" no menu de seleção (1 vídeo); tela com resolução (original/1080p/720p/480p, nunca aumenta) e presets por bits por pixel: Qualidade 0,055, Equilibrado 0,040 (padrão, calibrado pelo usuário), Máxima economia 0,030 e Personalizado. Alvo = bpp × L × A × fps, teto de 80% do bitrate de vídeo do original, piso de 400 kbps. HEVC fixo.
- [x] Estimativa de tamanho ao lado de cada preset "≈ X MB (−Y%)" (antecipada da Etapa 8) e aviso "Já está bem comprimido; ganho pequeno" (alvo acima do teto de 80% ou original HEVC/VP9/AV1).
- [x] Regras de áudio: AAC até 128 kbps em passthrough; senão AAC 128 kbps (96 no Máxima economia); mais de 2 canais com downmix para estéreo.
- [x] Seletor de destino com atalhos (mesma pasta do original, COMPRIMIDOS = Movies/COMPRIMIDOS, escolher outra pasta com o seletor do Fossify); lembra a última escolha.
- [x] Gravação: temporário no cache (apagado em qualquer desfecho) -> gravação direta + MediaScannerConnection; fallback MediaStore (IS_PENDING) em Movies/COMPRIMIDOS com aviso quando a gravação direta falha.
- [x] Nome `<original>_comp_q<N>.mp4` (N = bpp real do alvo × 1000), `_2` etc. se já existir, sem empilhar sufixos antigos; nunca sobrescreve.
- [x] Mostrar progresso (tela ligada), cancelamento e resultado (tamanho antes/depois, tempo, bitrate pedido x real, nome final).

## Etapa 3.5: Ajustes de interface (concluída)
- [x] Bug: puxar para atualizar disparava durante o toque longo e limpava a seleção. Agora fica desativado enquanto há seleção, e a atualização da lista nunca limpa a seleção (só remove itens que não existem mais). Não bastou (o dedo escorrega antes da seleção): puxar-para-atualizar desativado por completo nas telas de mídia; item "Atualizar" no menu de três pontos.
- [x] Modo lista: tamanho do arquivo (do banco) abaixo do nome, à esquerda da duração. Só na lista, fotos e vídeos. O tamanho vinha 0 porque o MediaFetcher só o buscava ao ordenar por tamanho; agora também busca no modo lista (coluna SIZE do MediaStore, com File.length() de reserva).
- [x] Modo lista: check de seleção sobre o canto superior esquerdo da miniatura, que escurece quando selecionada.
- [x] Menu de três pontos da seleção: "Girar", "Copiar para", "Criar atalho" e "Definir como" escondidos no código (XML intacto); "Comprimir" e "Informações para compressão" no topo.

## Etapa 4: Complementos do preset "Inteligente"
- [ ] Resolução padrão automática: >1080p -> 1080p; <=1080p mantém (hoje o padrão é manter a original e o usuário escolhe).
- [ ] Escolha da trilha de áudio (hoje só a trilha padrão é mantida).
- [ ] Avisar sobre legendas descartadas.
- [x] Descartar o resultado se ficar com 95% ou mais do tamanho do original (economia < 5%); nada gravado, aviso com os dois tamanhos. Constante `ResultVerifier.DISCARD_RATIO`.
- [ ] Calibração final dos bpp dos presets (comparando qualidade no celular).

## Etapa 5: Preservar data e apagar original (concluída)
- [x] Copiar a data do original para o comprimido: `File.setLastModified` + MediaStore (`DATE_MODIFIED`, `DATE_TAKEN`) + banco de datas do Fossify, se o original tinha entrada lá (`DatePreserver`).
- Fora desta etapa: data de criação nos metadados do MP4 (o Transformer não oferece isso de forma direta; seria reescrever o `mvhd`). Só retomar se a ordem na galeria falhar.
- [x] Opção "Depois de comprimir": manter (padrão) / lixeira do app / apagar de vez. Só executa após verificar o resultado (arquivo, trilha de vídeo, áudio se havia, duração ±1 s, resolução esperada). Lixeira desabilitada com explicação se a do Fossify estiver desligada. "Apagar de vez" nunca é lembrado e pede confirmação do app (nome e tamanho) a cada uso.
- [x] Testar que a ordem na galeria não muda (testado no aparelho, na Pasta Segura).
- [x] Verificação de data tolerante (±2 s, releitura após o scan) que informa o campo divergente; botão "Comprimir" na barra de seleção (à esquerda da lixeira, 1 vídeo); lista atualizada automaticamente ao fechar o resultado.

## Etapa 6: Fila de compressão (plano aprovado; fila única e permanente, um item por vez)
### 6a-1: Extração do CompressionPipeline (sem fila)
- [x] `CompressionPipeline` (engine, sem UI): descarte por economia < 5%, gravação no destino, data, verificação e remoção do original, via `OriginalRemover`. `CompressFlow` passa a usá-lo, sem mudar o comportamento.
- [x] Validado por mim no aparelho: um vídeo pelo caminho completo (compressão, gravação, data, original). Só então começa o 6a-2.

### 6a-2: Fila (motor)
- [x] Banco Room próprio `compression_queue.db` (fora do GalleryDatabase), repositório e `QueueRunner` (um item por vez; estados aguardando, comprimindo, concluído, falhou, descartado, cancelado; fase do item para recuperação).
- [x] `QueueService` em primeiro plano, tipo `mediaProcessing` (permissões no manifest), `onTimeout` (cancela o item atual, devolve a aguardando, grava "pausada pelo limite" e avisa), wake lock parcial por item, Transformer na thread principal.
- [x] Permissão de notificações pedida na primeira adição; notificação com "item i de N" e %, toque abre a fila, ações "Pausar" e "Pular item atual"; resumo final (concluídos, falhas, economia).
- [x] Todo "Comprimir" (1 ou vários vídeos) entra no fim da fila; diálogo de progresso antigo deixa de ser usado. Aviso rápido "Adicionado à fila (n.º X)" com ação "Ver fila", sem abrir a tela. Avisa se o vídeo já está na fila.
- [x] Preferência "Iniciar fila automaticamente" (padrão ligada). Pausar (termina o item atual, padrão) e "Pausar agora" (secundária).
- [x] Opções por adição com estimativa somada; aviso de vídeos já eficientes ("Adicionar todos mesmo assim" / "Adicionar só os demais").
- [x] "Apagar de vez": confirmação única por lote (quantidade e tamanho total). O consentimento vale só para aquele lote e para a sessão em que foi dado: ao retomar a fila em outro dia (ou após o processo ser morto), itens com "apagar de vez" voltam a pedir confirmação antes de apagar; nunca apaga sem passar pelo `ResultVerifier`.
- [x] Recuperação: item "comprimindo" volta a aguardando, temporário órfão apagado, 2 mortes seguidas no mesmo item = falhou; destino parcial apagado; fase "publicado" retoma só o pós-processamento. Banner "Fila interrompida: Retomar" ao abrir o app.
- [x] Falha de um item não para os demais; motivo gravado.
- [x] Tela mínima: lista com nome, estado e %; em itens concluídos, tamanho antes e depois; motivo quando falhou ou foi descartado; Iniciar/Pausar; `FLAG_KEEP_SCREEN_ON` enquanto a fila roda.
- [x] Testar: tela ligada, tela apagada, dentro da Pasta Segura (inclusive com o bloqueio automático dela), app morto no meio de um item, vídeo corrompido no lote, "apagar de vez" em lote pequeno, notificação negada.

### 6b: Tela da fila
- [ ] Visual final: remover, reordenar, cancelar item, cancelar a fila inteira (com confirmação), trocar destino e opções por item, limpar concluídos.
- [ ] Estimativa de tempo restante: velocidade medida em pixels por segundo dos itens concluídos (média recente, com valor padrão conservador baseado nos meus testes: cerca de 270 milhões de pixels por segundo em 720p, 24 fps), mostrada no topo da tela da fila ('Restante: ≈ X min (N itens)'), na notificação e no diálogo ao adicionar, sempre com '≈'. Dados já gravados por item desde a 6a-2: resolução de saída, fps, duração, início, fim e tempo só de compressão (`compress_ms`).
- [ ] Resumo da última execução persistido na tela da fila (concluídos, falhas, espaço economizado, horário de término), porque as notificações da Pasta Segura podem estar bloqueadas.
- [ ] Tela escura de baixo consumo (brilho mínimo, quase preta, só o progresso, texto que muda de posição; um toque volta ao normal).

## Etapa 7: Marca de "já comprimido"
- [ ] Protótipo: gravar marca nos metadados do MP4; verificar se sobrevive a mover/copiar.
- [ ] Registro no banco como reforço.
- [ ] Detecção de "já comprimido" considera os três sinais: sufixo `_comp_q<N>` no nome, marca nos metadados e registro no banco.
- [ ] Selo visual na galeria; aviso ao tentar recomprimir.

## Etapa 8: Estimativa de tamanho
(A estimativa por preset já existe desde a Etapa 3.)
- [ ] Mostrar "original / resolução original / 720p" lado a lado antes de comprimir, se ainda não existir.

## Etapa 9: Polimento
- [ ] Vídeos HDR/10 bits, 60 fps, formatos que o celular não decodifica, arquivos corrompidos, vídeos muito longos, pouco espaço em disco.
- [ ] Pausa automática da fila por status térmico severo (`PowerManager` thermal status), retomando quando o aparelho esfriar.
- [ ] Ajustes de interface e correção de bugs.
- Pendente de teste com amostras reais: áudio 5.1/7.1 (downmix), canais desconhecidos, vídeo vertical, 4K/HDR, MKV com várias trilhas e legendas, arquivos maiores que 1,5 GB.
