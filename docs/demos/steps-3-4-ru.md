# Учебные демо шагов 3 и 4

Оба класса с методом `main` находятся в пакете `ee.ut.proofofprompt.proof_of_prompt.demos`, рядом с `ReceiptDemo`. Каждый запуск `save`, `429` или `live` добавляет один учебный запрос в выбранную базу. Режим `read` только читает данные.

## Подготовка

Запусти PostgreSQL. В базе проекта должны быть установлены обе схемы из шагов 3 и 4. В текущей версии проекта метод записи добавляет строку в `model_executions` даже для примера шага 3. Если шаг 4 уже работает, база готова.

Открой PowerShell в корне Maven-проекта. Введи пароль роли приложения. По умолчанию проект подключается к `127.0.0.1:5432/proof_of_prompt` от имени `proof_of_prompt_app`. Для другой базы задай также `PROOF_DATABASE_URL` и `PROOF_DATABASE_USER`.

```powershell
$env:PROOF_DATABASE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Application database password' -AsSecureString)).Password
$demoPackage = 'ee.ut.proofofprompt.proof_of_prompt.demos'
```

Команды ниже запускают консольные приложения в отдельных процессах. Веб-сервер проекта для них запускать не требуется.

## Шаг 3. Запись и чтение после перезапуска

1. Создай пример из двух сообщений. Демо построит дерево, подпишет квитанцию временным учебным ключом и передаст запрос и квитанцию в репозиторий. Скопируй из консоли `Accepted request ID` и `Signed receipt hash`.

```powershell
.\mvnw.cmd "-Dspring-boot.run.main-class=$demoPackage.PostgresEvidenceDemo" '-Dspring-boot.run.arguments=save' spring-boot:run
```

2. Первый процесс Java уже завершился. Чтобы проверить также перезапуск PostgreSQL, можно в этот момент перезапустить тот же экземпляр базы с тем же каталогом данных. Для чтения вставь UUID из первого запуска.

```powershell
$acceptedRequestId = Read-Host 'Accepted request ID'
.\mvnw.cmd "-Dspring-boot.run.main-class=$demoPackage.PostgresEvidenceDemo" "-Dspring-boot.run.arguments=read $acceptedRequestId" spring-boot:run
```

3. Сравни строки `Signed receipt hash`. Хеш охватывает тело квитанции и подпись. При чтении также появятся позиции и роли восстановленных сообщений: `0 (SYSTEM)`, `1 (USER)`. Режим чтения подходит и для UUID, напечатанного в демо шага 4.

**Куда зайти в отладчике.** Начни с `PostgresEvidenceDemo.saveExampleRequest`. На вызове `saveAcceptedRequestEvidence` перейди в `AcceptedRequestEvidenceRepository`: первый `INSERT` сохраняет квитанцию, `batchUpdate` сохраняет сообщения, последний `INSERT` ставит модели статус `NOT_STARTED`. Все три записи входят в одну транзакцию. Для чтения начни с `PostgresEvidenceDemo.readSavedRequest` и зайди в `findAcceptedRequestEvidence`. Сначала читается квитанция, потом сообщения по порядку, затем `CommittedRequest.restore` заново строит дерево.

## Шаг 4. Принятие запроса и обращение к Groq

**Воспроизводимая ошибка.** Запусти пример с локальным HTTP-сервером. Он возвращает код 429 на запрос настоящего класса `GroqChatClient`. Понадобится только подключение к PostgreSQL.

```powershell
.\mvnw.cmd "-Dspring-boot.run.main-class=$demoPackage.GroqRequestDemo" '-Dspring-boot.run.arguments=429' spring-boot:run
```

Ожидаемые строки при доступной базе:

```text
Acceptance status: ACCEPTED
Model execution status: FAILED
Failure: Groq returned HTTP 429
Model outcome saved: true
Identical signed receipt read from PostgreSQL.
```

**Реальный запрос.** Режим `live` использует профиль `model-request`, существующий ключ подписи сервиса и API-ключ Groq. Если эти переменные уже заданы в текущем PowerShell, сразу запускай демо. Для стандартного расположения хранилища ключей настройка выглядит так:

```powershell
$env:PROOF_SIGNING_KEYSTORE = Join-Path $env:LOCALAPPDATA 'ProofOfPrompt\receipt-signer.p12'
$env:PROOF_SIGNING_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Receipt signing keystore password' -AsSecureString)).Password
$env:GROQ_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'Groq API key' -AsSecureString)).Password
.\mvnw.cmd "-Dspring-boot.run.main-class=$demoPackage.GroqRequestDemo" '-Dspring-boot.run.arguments=live' spring-boot:run
```

Демо отправляет два фиксированных сообщения: системную инструкцию `Answer in one short sentence.` и вопрос `What is an apple?`. По умолчанию модель — `openai/gpt-oss-20b`. При успехе появятся `SUCCEEDED` и текст ответа. При ошибке появятся `FAILED` и её причина. В обоих случаях демо читает квитанцию обратно по UUID и сравнивает её с выданной. Строка `Model outcome saved` отдельно показывает, удалось ли записать результат попытки вызова модели.

**Куда зайти в отладчике.** Поставь точку останова в `RequestProcessingService.acceptAndCallModel` на вызове `saveAcceptedRequestEvidence`. Сделай шаг через эту строку. До вызова `groqClient.callModel` квитанция уже видна в DBeaver, а статус модели — `NOT_STARTED`. UUID можно взять из `committedRequest.acceptedRequestId()` в отладчике. Затем зайди в `GroqChatClient.callModel`: посмотри на `payload` (JSON с моделью и сообщениями), на вызов `httpClient.send` и на `response.statusCode()`. После возврата из клиента `ModelExecutionRepository.recordFinalOutcome` обновит статус. Повтори SQL-запрос ниже, чтобы увидеть результат.

Для запуска прямо из IntelliJ выбери главный класс `PostgresEvidenceDemo` или `GroqRequestDemo`. В **Program arguments** укажи те же аргументы, например `read твой-UUID` или `429`. Переменные окружения должны быть доступны самому процессу Java. Переменные из отдельного окна PowerShell уже открытая IntelliJ автоматически не получит.

## Тот же запрос в DBeaver

В каждом запросе подставь UUID из консоли или отладчика. Первый запрос покажет в одной строке принятие запроса и состояние вызова модели. Второй покажет два учебных сообщения в исходном порядке.

```sql
SELECT accepted_requests.accepted_request_id, accepted_requests.request_acceptance_status,
       model_executions.execution_status, model_executions.failure_description,
       convert_from(model_executions.model_response_utf8, 'UTF8') AS model_response
FROM proof_of_prompt.accepted_requests
JOIN proof_of_prompt.model_executions USING (accepted_request_id)
WHERE accepted_requests.accepted_request_id = 'YOUR-REQUEST-UUID';

SELECT message_position, message_role, convert_from(message_content_utf8, 'UTF8') AS message_content
FROM proof_of_prompt.request_messages
WHERE accepted_request_id = 'YOUR-REQUEST-UUID'
ORDER BY message_position;
```
