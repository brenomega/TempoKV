# WAL, snapshots e recuperação — etapa 5

## WAL segmentado

O WAL usa segmentos `segment-<índice de 20 dígitos>.wal`, limitados a 16 MiB
em produção. Registros nunca atravessam segmentos.

Cada registro possui:

| Campo | Tipo | Finalidade |
|---|---|---|
| magic | `int` | identifica TempoKV WAL (`TKV1`) |
| format | `short` | versão do codec, atualmente `1` |
| payload length | `int` | delimita o registro e limita seu tamanho a 64 MiB |
| payload | bytes | versão, instante e mutações do `CommitRecord` |
| checksum | `int` | CRC-32 do payload |

O `CommitCoordinator` grava todos os bytes e aplica `fsync` antes de publicar no
`StorageEngine`. Uma falha impede a publicação e retorna erro ao cliente. A
política de produção é `ALWAYS`; `NEVER` existe somente para cenários
explicitamente relaxados e testes.

O replay percorre os segmentos sem materializar o WAL inteiro. Versões devem ser
estritamente crescentes. Uma cauda incompleta do último append é truncada na
abertura; magic, formato, tamanho, ordem ou checksum inválidos bloqueiam a
recuperação.

## Snapshots

Snapshots são nomeados pelo corte, possuem versão de formato e checksum e
incluem:

- cadeias MVCC retidas;
- limites que distinguem histórico coletado de chave inexistente;
- entradas do índice de TTL.

O arquivo temporário é sincronizado antes do rename atômico, e o diretório é
sincronizado depois da publicação. São mantidos os dois cortes mais recentes. Se
o mais novo estiver inválido, a recuperação tenta o anterior; sem snapshot
válido, o replay começa no WAL completo.

No encerramento ordenado, o servidor aplica retenção, publica um snapshot e
compacta conservadoramente apenas até o snapshot válido mais antigo. Assim, o
snapshot anterior continua sendo um fallback suficiente. As métricas
`snapshot.duration`, `snapshot.successes`, `snapshot.failures`,
`snapshot.version`, `wal.compacted_through`, `wal.bytes`,
`recovery.duration` e `recovery.version` registram custo e resultado.

Falhas de snapshot ou compactação colocam a saúde do nó em `DEGRADED` sem
invalidar o snapshot previamente publicado.

## Expiração

O `ExpirationWorker` consulta o índice a cada 10 ms. Apenas uma entrada ainda
associada à cabeça atual produz `EXPIRED_TOMBSTONE`; entradas obsoletas são
descartadas. O tombstone passa pelo mesmo WAL/fsync e mantém no histórico o
motivo `EXPIRED`. Após restart, snapshot e WAL restauram os vencimentos pendentes
antes de abrir o endpoint RESP.
