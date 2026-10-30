# CLAUDE.md

App Android pessoal: fork do Fossify Gallery + compressão de vídeo em fila.
Detalhes do que fazer: `docs/SPEC.md`. Etapas: `docs/PLANO.md`.

## Regras de trabalho
- Trabalhe SOMENTE na etapa pedida do PLANO.md. Não adiante etapas.
- Faça mudanças pequenas e pontuais. Não reescreva arquivos inteiros sem necessidade.
- Antes de criar algo novo, veja como o Fossify já faz algo parecido e siga o mesmo padrão.
- Ao terminar uma etapa: compile (`./gradlew assembleFossDebug`), corrija os erros, marque a etapa no PLANO.md e sugira a mensagem de commit.
- Em caso de dúvida de requisito, pergunte antes de implementar.
- Responda de forma curta. Não repita o código que você acabou de escrever.

## Stack
- Kotlin, telas em XML/Views (padrão do Fossify). NÃO migrar para Compose.
- Media3 Transformer para compressão. Room/banco já existente do Fossify para registros novos, se necessário.
- Alvo: Android 16 (API 36), Galaxy S23. Respeitar scoped storage (MediaStore / SAF).

## Comandos
Rodar a partir da raiz do repositório com o ambiente:
- `export JAVA_HOME=$HOME/.sdkman/candidates/java/current`
- `export ANDROID_HOME=$HOME/Android/Sdk` (adb em `$ANDROID_HOME/platform-tools`)
- `export ANDROID_SERIAL=<ip:porta>` se `adb devices` listar o S23 duas vezes (senão o install falha)

- Build debug: `./gradlew assembleFossDebug`
- Instalar no celular (adb): `./gradlew installFossDebug`
- Logs: `adb logcat -s <TAG>`
- O namespace do código continua `org.fossify.gallery`; só o applicationId é `com.lucca.galeria` (debug: sufixo `.debug`).

## Convenções
- applicationId: `com.lucca.galeria` (não usar o id do Fossify original).
- Código novo em pacote/arquivos separados quando possível, para facilitar merges com o upstream do Fossify.
- Strings novas em `strings.xml` (pt-BR).
- Licença GPL-3.0 do Fossify é mantida.

## Ambiente do aparelho (Pasta Segura)
- O app é instalado pelo adb no ambiente normal do celular (usuário 0).
- O usuário adiciona o app à Pasta Segura do Samsung e usa a cópia de lá (arquivos em `/storage/emulated/150`).
- Atualizações feitas pelo adb chegam à cópia da Pasta Segura, porque é o mesmo pacote. Se um dia não chegarem, remover o app da Pasta Segura e adicionar de novo.
- As Etapas 5 (apagar original) e 6 (serviço em primeiro plano com tela apagada) devem ser testadas dentro da Pasta Segura, com atenção ao bloqueio automático dela.
- Configurações e banco do app são separados por perfil.
- Marca de "já comprimido" (Etapa 7), em `compression/mark/`: A = sufixo `_comp_q<N>` no nome; B = box `uuid` "q=<N>;v=1" anexado ao fim do MP4 (`Mp4Mark`), escrito só em resultados novos do app e lido só ao comprimir (vai no cache de análise); C = chave tamanho + duração em segundos na tabela `compressed_keys` (`CompressedMarks`, carregada uma vez e mantida em memória). A lista nunca abre arquivos (usa C e A). Divergência: vale B. Nunca anexar nada a arquivos que o app não gerou.
- O S23 (One UI 8.5) recusa o tipo de serviço em primeiro plano `mediaProcessing` (o sistema o trata como "none" e lança `InvalidForegroundServiceTypeException`). O `QueueService` tenta `mediaProcessing` e, nessa exceção, usa `dataSync` (declarado no manifest com a permissão `FOREGROUND_SERVICE_DATA_SYNC`). Não remover o fallback.
- Pausa por temperatura (`ThermalGuard`, limites como constantes no topo do arquivo): durante a pausa o `QueueService` continua em primeiro plano sem runner (o Android não deixa reiniciá-lo do segundo plano), com wake lock próprio `galeria:compression-cooldown`. Só `StopReason.THERMAL` é retomado sozinho; pausa manual prevalece. Log: `adb logcat -s ThermalGuard`. Simulação: `adb shell cmd thermalservice override-status <n>` e `adb shell dumpsys battery set temp <décimos de °C>`; ao fim, sempre `adb shell cmd thermalservice reset` e `adb shell dumpsys battery reset`.
- `files/` é a lixeira do Fossify (`recycleBin = filesDir`): esvaziar a lixeira apaga a pasta inteira. Nada que precise durar pode ficar lá.
- Registro de temperatura (`ThermalLog`, só observa): `no_backup/thermal_log.csv` (horário ISO, evento, status, bateria °C, carregando, tela interativa, id do item, motivo). Eventos START/END, PAUSA, RETOMADA, EMERGENCIA, DESISTENCIA, STATUS, PREF, PAUSA_MANUAL, ERRO_MUXER, ERRO_MUXER_RETRY e SAMPLE a cada 60 s com a fila rodando ou em pausa por calor. Linhas com mais de 30 dias são descartadas; ~2 MB no máximo. Separado por perfil. Para puxar:
  - Perfil normal: `adb exec-out run-as com.lucca.galeria.debug cat no_backup/thermal_log.csv > thermal_log_u0.csv`
  - Pasta Segura: `adb exec-out run-as com.lucca.galeria.debug --user 150 cat no_backup/thermal_log.csv > thermal_log_u150.csv`
  - Mesmo padrão para os bancos (`databases/compression_queue.db`, com `-wal` e `-shm`).
- Muxer: `MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS` (60 s) em `VideoCompressor`. Na fila, `ERROR_CODE_MUXING_TIMEOUT` refaz o item uma vez sem contar como falha; o segundo erro igual é falha normal.
- Testes de JVM: `./gradlew testFossDebugUnitTest`.
