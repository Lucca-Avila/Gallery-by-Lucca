# SPEC: Galeria + Compressor (uso pessoal)

## Visão
Fork do Fossify Gallery (GPL-3.0) com compressão de vídeo embutida. App só para uso próprio, instalado direto no celular (sem Play Store).

- Aparelho alvo: Galaxy S23 5G, Android 16, One UI 8.5.
- Manter TODAS as funções do Fossify (fotos, edição, lixeira, etc.).
- applicationId novo (ex.: `com.lucca.galeria`) para poder coexistir com o Fossify original instalado.

## Funções novas (em ordem de prioridade)

### 1. Ordenar por duração
- Nova opção de ordenação dentro de uma pasta: duração (crescente/decrescente).
- Fotos (sem duração) ficam agrupadas no fim ou no início, de forma previsível.

### 2. Compressão de vídeo
- Biblioteca: Media3 Transformer (codificador de hardware).
- Seleção: tocar num vídeo ou selecionar vários na galeria e escolher "Comprimir".
- Implementado (Etapa 3): tela "Comprimir vídeo" para um vídeo, com resolução (original/1080p/720p/480p pelo lado menor, nunca aumenta) e presets de qualidade por bits por pixel (bpp):
  - Qualidade 0,055 bpp, Equilibrado 0,040 bpp (padrão, calibrado pelo usuário), Máxima economia 0,030 bpp e Personalizado (slider).
  - Bitrate alvo = bpp × largura × altura × fps da resolução escolhida, limitado a 80% do bitrate de vídeo do original, com piso de 400 kbps (nunca acima do original). Recalcula ao mudar a resolução. Constantes em `CompressionPlanner`.
  - Codec H.265 (HEVC) fixo (H.264 só se o aparelho não tiver encoder HEVC), taxa de quadros original.
  - Estimativa de tamanho ao lado de cada preset: (vídeo + áudio) × duração ÷ 8, "≈ X MB (−Y%)" (antecipada da Etapa 8).
  - Aviso (sem bloquear) "Já está bem comprimido; ganho pequeno" quando o alvo do preset passa do teto de 80% ou o original já é HEVC/VP9/AV1.
  - Áudio: AAC até 128 kbps = passthrough; AAC acima disso ou outro formato = AAC 128 kbps (96 kbps no Máxima economia); mais de 2 canais = downmix para estéreo (ChannelMixingAudioProcessor) + AAC no mesmo alvo. Só a trilha padrão é mantida.
- Preset "Inteligente" original (a evoluir na Etapa 4): >1080p reduz para 1080p; <=1080p mantém resolução. Se o resultado ficar maior ou quase igual ao original: descartar o resultado e avisar (pendente).
- Opção manual: forçar resolução menor (ex.: 1080p -> 720p), ajustar bitrate/qualidade (feito via resolução + Personalizado).
- Prioridade é tamanho pequeno com qualidade boa para tela de celular, não fidelidade máxima.
- Destino do arquivo comprimido: padrão por lote, com atalhos "mesma pasta do original", "COMPRIMIDOS" (`Movies/COMPRIMIDOS`, criada pelo app) e "escolher outra pasta..." (PickDirectoryDialog do Fossify, qualquer pasta). Ajuste por item na tela da fila. Lembra a última escolha.
- Nome do arquivo: `<original>_comp_q<N>.mp4`, onde N = bits por pixel REAL do alvo de vídeo (depois do teto, do piso e da resolução) × 1000, arredondado (ex.: `video_comp_q40.mp4`). Nome repetido no destino vira `video_comp_q40_2.mp4`; nunca sobrescreve. Se o original já termina em `_comp`, `_comp_q<N>` ou `_comp_q<N>_<n>`, o sufixo antigo é removido antes de montar o novo (sem empilhar).
- Gravação: primeiro num arquivo temporário no cache do app (apagado em qualquer desfecho), depois cópia ao destino. "Mesma pasta" e "outra pasta" usam gravação direta (MANAGE_EXTERNAL_STORAGE, já concedida) e depois avisam o MediaStore (MediaScannerConnection). "COMPRIMIDOS" e o fallback usam MediaStore com IS_PENDING. Se a gravação direta falhar (pasta inexistente, sem permissão, erro na cópia), cai em `Movies/COMPRIMIDOS` via MediaStore e avisa no resultado.
- Após comprimir: oferecer apagar o original (sempre com confirmação do sistema).
- O vídeo comprimido mantém a data original, para não bagunçar a ordem. Como os vídeos são baixados, normalmente não há data de gravação: copiar a data de modificação do arquivo original (e a data de gravação, se existir).

#### Origem variada (vídeos baixados da internet)
Os vídeos NÃO são gravados no celular, então as specs são imprevisíveis. O app deve analisar cada arquivo antes de comprimir (resolução, codec, bitrate, fps, rotação, trilhas de áudio/legenda, HDR) e decidir sozinho:
- Contêineres e codecs variados (MP4, MKV, WebM, AVI; H.264, HEVC, VP9, AV1 etc.): o que o celular não decodifica deve falhar com mensagem clara, sem travar a fila.
- Saída sempre em MP4 (H.265 + AAC). A extensão/contêiner pode mudar em relação ao original.
- Vídeo que já está com bitrate baixo ou em codec eficiente (HEVC/AV1 já pequenos): se a economia prevista for pequena (ex.: < 20%), avisar e pular, em vez de recomprimir e perder qualidade à toa.
- Bitrate alvo ADAPTATIVO: nunca acima do bitrate original; calcular a partir da resolução e do bitrate real do arquivo, não de um valor fixo.
- Resoluções fora do padrão (vertical, ultrawide, 4K, 1440p, 360p): aplicar a regra "limitar em 1080p do lado maior/menor" mantendo a proporção; nunca aumentar resolução.
- Taxa de quadros variável ou alta (50/60 fps): manter por padrão; oferecer opção de limitar a 30 fps para economizar mais.
- Várias trilhas de áudio e legendas (comum em MKV): o Transformer pode manter só uma trilha de áudio e descartar legendas. Avisar ANTES de comprimir e deixar escolher a trilha de áudio; se houver legenda, avisar que será perdida.
- Áudio (implementado): AAC até 128 kbps = passthrough; senão AAC 128 kbps (96 no Máxima economia); mais de 2 canais = downmix para estéreo antes de recodificar. AAC de bitrate desconhecido fica em passthrough.
- HDR/10 bits (comum em 4K baixado): testar; decidir entre manter HDR ou avisar sobre cores alteradas.
- Vídeos corrompidos ou incompletos: pular com erro registrado.

### 3. Fila de compressão
- Adicionar vários vídeos; processar um após o outro.
- Serviço em primeiro plano com notificação de progresso (funciona com tela apagada).
- Tela da fila: ver, remover, reordenar, cancelar itens; ver status; trocar o destino de um item.
- Cada item pode ter sua própria configuração, com um padrão aplicado ao lote.
- Pausa automática por temperatura (opção "Pausar se esquentar" no menu da fila, ligada por padrão):
  - Pausa se status térmico ≥ SEVERE **ou** bateria ≥ 43 °C: termina o item atual e para antes do próximo.
  - Emergência (status ≥ CRITICAL ou bateria ≥ 46 °C): cancela o item atual, que volta a aguardar; o temporário é apagado e o original não é tocado. Se o item já estiver gravando/verificando, espera terminar (segundos).
  - Retoma sozinha com status ≤ LIGHT **e** bateria ≤ 40 °C **e** pelo menos 5 min de pausa. Sem esfriar em 60 min, para de vez ("toque para retomar"), porque o tempo pausado conta no limite diário do serviço.
  - Pausa manual nunca é retomada pelo app e prevalece sobre a térmica. "Retomar agora" ainda quente: só a emergência pausa de novo até a primeira leitura fria. Desligar a opção durante a pausa retoma.
  - Avisos (canal do resumo) ao pausar, retomar e desistir; notificação fixa "Fila pausada por temperatura" com "Retomar agora" e "Pausar"; linha "Pausada por temperatura às HH:MM (n vezes)" no resumo da execução.

### 4. Marca de "já comprimido"
- Gravar a marca dentro do próprio arquivo (metadados do MP4), para sobreviver à mudança de pasta/renomeação.
- Reforço: registro no banco do app.
- Indicador visual na galeria (ícone/selo no vídeo comprimido).
- Pontos a validar em protótipo: a marca sobrevive a mover/copiar/enviar por outros apps?
- Evitar recomprimir vídeos já marcados (avisar antes).
- A detecção de "já comprimido" deve considerar três sinais: o sufixo `_comp_q<N>` no nome, a marca nos metadados do MP4 e o registro no banco.
- Implementado (Etapa 7):
  - A: sufixo `_comp_q<N>` no nome.
  - B: box `uuid` com "q=<N>;v=1" anexado ao fim do resultado antes do `ResultVerifier` (que confere a marca); sem recodificar. Lido só ao tocar em Comprimir, junto com a análise (fica no cache). Sobrevive a copiar, mover e renomear; envio por apps que recodificam a perde.
  - C: chave tamanho exato + duração em segundos (os valores do `Medium`), gravada com os valores do MediaStore após o scan do resultado. Vídeos listados com `_comp_q<N>` sem chave no banco são adotados (chave com o N do nome, em segundo plano, sem abrir o arquivo). Risco de falso positivo: dois vídeos com o mesmo tamanho até o byte e a mesma duração.
  - Selo q<N> igual para as três camadas; a lista usa C, senão A. Ao comprimir: B > C > A (vale a marca). 1 vídeo: aviso com "Comprimir mesmo assim"; lote: selo "já comprimido" e "Remover os já comprimidos".

### 5. Estimativa de tamanho (menos importante)
- Já existe (Etapa 3): estimativa por preset na tela de compressão, "≈ X MB (−Y%)", para a resolução escolhida e atualizada ao mudá-la.
- Cálculo: (bitrate de vídeo + bitrate de áudio) × duração ÷ 8, com o bitrate de áudio resultante das regras de áudio. Precisão aproximada é suficiente (o encoder pode variar cerca de ±20%).
- Falta (Etapa 8): mostrar lado a lado "Original: X MB | Resolução original: ~Y MB | 720p: ~Z MB".
- Com origem variada, a estimativa também apoia o aviso "Já está bem comprimido; ganho pequeno" (já existe). Pular automaticamente esses vídeos é da fila (Etapa 6).

## Fora do escopo (v1)
- iOS, desktop, publicação na Play Store, nuvem, login.
- Editar vídeo (cortar, filtros) além do que o Fossify já tem.
