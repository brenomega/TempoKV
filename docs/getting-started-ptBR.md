# Primeiro uso

Este tutorial inicia um nó TempoKV local, grava duas versões de um valor e lê
seus estados atual e histórico.

## Pré-requisitos

- JDK 25
- um shell semelhante ao POSIX
- `redis-cli`

Docker não é necessário para este tutorial.

## 1. Gere o TempoKV

A partir da raiz do repositório:

```bash
./gradlew clean build
```

O JAR executável é criado em `build/libs/tempokv-0.1.0.jar`.

## 2. Inicie um nó local

Use um novo diretório de dados para que a versão do primeiro commit seja
previsível:

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/tutorial \
  --persistence-enabled=true \
  --authentication-enabled=false
```

Esta é uma configuração deliberadamente local:

- o endereço de bind padrão é `127.0.0.1`;
- RESP escuta na porta `6379`;
- SQL escuta na porta `6380`;
- a autenticação é desabilitada explicitamente;
- alterações confirmadas são persistidas no diretório selecionado.

Deixe esse processo em execução.

## 3. Conecte-se

Abra outro terminal:

```bash
redis-cli -p 6379
```

Verifique a conexão:

```text
127.0.0.1:6379> PING
PONG
```

## 4. Grave e leia um valor

Crie a primeira versão:

```text
127.0.0.1:6379> SET profile first
OK
127.0.0.1:6379> GET profile
"first"
```

Crie uma segunda versão:

```text
127.0.0.1:6379> SET profile second
OK
127.0.0.1:6379> GET profile
"second"
```

Leia o valor como ele existia na versão 1:

```text
127.0.0.1:6379> GETAT profile VERSION 1
"first"
```

`GETAT` não altera o valor atual. `GET profile` continua retornando `"second"`.

Se o diretório de dados não estava vazio, execute `HISTORY profile` e use uma
das versões retidas exibidas, em vez de presumir a versão 1.

## 5. Encerre com segurança

Saia do `redis-cli` e pressione `Ctrl+C` no terminal do servidor. O shutdown
hook fecha os endpoints de rede, interrompe os workers em segundo plano,
publica um snapshot final quando a persistência está habilitada e libera o lock
do diretório de dados.

## Solução de problemas

### A inicialização exige credenciais de autenticação

A autenticação é habilitada por padrão e não possui credenciais padrão. Para
um nó local protegido, substitua `--authentication-enabled=false` por valores
explícitos:

```bash
--authentication-enabled=true \
--authentication-username=operator \
--authentication-password='<escolha-um-segredo>'
```

Então autentique com `AUTH operator <escolha-um-segredo>` antes dos demais
comandos.

### A porta já está em uso

Selecione portas livres com `--resp-port=<porta>` e `--sql-port=<porta>` e
informe a porta RESP ao `redis-cli`.

### O bind fora do loopback é rejeitado

TempoKV não possui TLS nativo. Um bind fora do loopback deve definir
explicitamente `--allow-insecure-remote-transport=true` e deve ser usado
somente em rede confiável ou atrás de terminação TLS.

### Um segundo nó não consegue usar o mesmo diretório

Cada nó em execução precisa de um diretório de dados exclusivo. Encerre o
primeiro nó ou escolha outro `--data-dir`.

## Próximos passos

- Experimente outras operações no
  [livro de receitas de comandos](command-cookbook-ptBR.md).
- Leia o comportamento ponta a ponta nos [casos de uso](use-cases-ptBR.md).
