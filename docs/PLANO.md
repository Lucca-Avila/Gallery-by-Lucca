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
- [ ] Adicionar dependência Media3 Transformer.
- [x] Criar um "analisador de vídeo" que lê resolução, codec, bitrate, fps, rotação, trilhas de áudio/legenda e HDR do arquivo (MediaExtractor/MediaMetadataRetriever). Será usado em todas as etapas seguintes.
  - Pendente de teste com amostras reais: vários áudios, HDR, vertical, >1,5 GB, AV1/VP9 (cobrir na Etapa 9)
- [ ] Testar com uma amostra variada de vídeos baixados (MP4, MKV, WebM; H.264, HEVC, VP9, AV1; com várias trilhas de áudio).
- [ ] Ação "Comprimir" no vídeo; tela simples com opções (resolução, H.265, bitrate).
- [ ] Seletor de destino com atalhos (mesma pasta do original, COMPRIMIDOS, escolher outra); lembrar a última escolha.
- [ ] Salvar o arquivo novo no destino; sufixo se o nome já existir (nunca sobrescrever).
- [ ] Mostrar progresso e resultado (tamanho antes/depois).

## Etapa 4: Regras do preset "Inteligente"
- [ ] >1080p -> 1080p; <=1080p mantém resolução; bitrate alvo adaptativo (a partir do bitrate real do arquivo, teto ~70% do original, nunca aumentar).
- [ ] Pular/avisar quando o vídeo já estiver eficiente (economia prevista < 20%).
- [ ] Tratar trilhas de áudio múltiplas (escolher trilha) e avisar sobre legendas descartadas.
- [ ] Descartar resultado se não ficar menor; avisar.
- [ ] Opção manual de reduzir resolução.
- [ ] Calibrar bitrates alvo comparando qualidade no celular.

## Etapa 5: Preservar data e apagar original
- [ ] Copiar data de gravação/modificação do original para o comprimido (MediaStore + metadados do MP4).
- [ ] Oferecer apagar o original (confirmação do sistema).
- [ ] Testar que a ordem na galeria não muda.

## Etapa 6: Fila de compressão
- [ ] Serviço em primeiro plano + notificação de progresso.
- [ ] Seleção múltipla na galeria -> "Comprimir na fila".
- [ ] Tela da fila (ver, remover, reordenar, cancelar, trocar destino de um item).
- [ ] Testar com tela apagada e com vários vídeos.

## Etapa 7: Marca de "já comprimido"
- [ ] Protótipo: gravar marca nos metadados do MP4; verificar se sobrevive a mover/copiar.
- [ ] Registro no banco como reforço.
- [ ] Selo visual na galeria; aviso ao tentar recomprimir.

## Etapa 8: Estimativa de tamanho
- [ ] Calcular tamanho previsto (bitrate x duração + áudio).
- [ ] Mostrar "original / resolução original / 720p" antes de comprimir.

## Etapa 9: Polimento
- [ ] Vídeos HDR/10 bits, 60 fps, formatos que o celular não decodifica, arquivos corrompidos, vídeos muito longos, pouco espaço em disco.
- [ ] Ajustes de interface e correção de bugs.
