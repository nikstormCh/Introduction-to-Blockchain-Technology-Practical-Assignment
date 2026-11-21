# Шаг 4. Принятие запроса и вызов Groq

Выполняй команды в PowerShell из корня Maven-проекта. Для локального запуска нужны PostgreSQL 18,
доступ к Groq и JDK 21 с утилитой keytool.

## 1. Обновление существующей базы шага 3

Останови Java-приложение на время миграции. PostgreSQL должен работать. Если сервер запущен
через pg_ctl, запускай его из отдельного окна PowerShell: Ctrl+C в общей консоли может
остановить и Java, и PostgreSQL. Перед запуском приложения проверь доступность сервера:

~~~powershell
& 'C:\Program Files\PostgreSQL\18\bin\pg_isready.exe' -h 127.0.0.1 -p 5432
~~~

Ожидается accepting connections. Выполни миграцию под владельцем базы:

~~~powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -X -W -h 127.0.0.1 -p 5432 -U postgres -d proof_of_prompt -v ON_ERROR_STOP=1 -v app_role=proof_of_prompt_app -f .\src\main\resources\db\step4-model-executions.sql
~~~

Здесь нужен пароль postgres. Миграция создаёт таблицу model_executions и добавляет запись
NOT_STARTED для каждого запроса шага 3. Ранее записанные исходы сохраняются при повторном запуске.
Для пустой установки скрипт Initialize-EvidenceDatabase.ps1 теперь выполняет оба шага.

## 2. Автоматические проверки

~~~powershell
.\scripts\Test-PostgresEvidence.ps1
~~~

Скрипт проверяет сценарии HTTP 200 и 429 в отдельной базе с локальным HTTP-сервером.
Во время вызова модель уже должна видеть эффект завершённой транзакции: тестовый обработчик
открывает отдельное подключение к PostgreSQL и читает квитанцию. После ошибки тест повторно
читает квитанцию и проверяет её байты, хеш, подпись и раскрытое сообщение. При ошибке вставки
сообщения обращений к модели должно быть ноль. Для этих тестов ключ Groq не требуется.

## 3. Реальный запрос

В первом окне PowerShell задай системную инструкцию и запусти приложение:

~~~powershell
$env:PROOF_SYSTEM_INSTRUCTION = 'Отвечай по-русски одним коротким предложением.'
.\scripts\Start-RequestWorkflow.ps1 -CreateSigningKey
~~~

При первом запуске придумай пароль для нового файла ключа подписи (не менее 6 символов),
затем введи существующий пароль пользователя proof_of_prompt_app и API-ключ Groq.
Файл receipt-signer.p12 создаётся в %LOCALAPPDATA%\ProofOfPrompt.
Сохрани файл и пароль для следующих запусков. Повторный запуск использует команду
.\scripts\Start-RequestWorkflow.ps1 без параметра создания.
Для передачи открытого сертификата проверяющему можно отдельно выполнить:

~~~powershell
keytool -exportcert -rfc -alias receipt-signer -keystore "$env:LOCALAPPDATA\ProofOfPrompt\receipt-signer.p12" -file receipt-signer-public.pem
~~~

После сообщения Started ProofOfPromptApplication выполни во втором окне:

~~~powershell
$payload = @{ text = 'Что такое яблоко?' } | ConvertTo-Json -Compress
$result = $null
$result = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/requests' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -ErrorAction Stop
$result | ConvertTo-Json -Depth 6
~~~

При успехе проверь executionStatus = SUCCEEDED, текст в modelResponse, modelOutcomeSaved = true
и acceptedRequestId в квитанции. В DBeaver можно найти строку по тому же UUID:

~~~sql
SELECT accepted_request_id, execution_status, convert_from(model_response_utf8, 'UTF8') AS model_response,
       failure_description
FROM proof_of_prompt.model_executions;
~~~

По умолчанию модель openai/gpt-oss-20b получает system и user, reasoning_effort = low,
max_completion_tokens = 1024 и stream = false. Профиль model-request слушает 127.0.0.1.
ID модели можно задать через PROOF_MODEL_ID, адрес базы и роль через настройки шага 3.

### Отдельная проверка роли developer

После успешного базового прогона останови приложение через Ctrl+C. В первом окне задай
следующую переменную, перезапусти приложение и отправь тот же вопрос из второго окна.

~~~powershell
$env:PROOF_DEVELOPER_INSTRUCTION = 'Use plain language.'
.\scripts\Start-RequestWorkflow.ps1
~~~

Получится последовательность system, developer, user. Положительный результат реального
запроса показывает, что провайдер принял такую форму. При отказе изучи HTTP-статус в
failureDescription. Убрать дополнительную инструкцию перед следующим запуском можно через
Remove-Item Env:PROOF_DEVELOPER_INSTRUCTION. Сервис сохраняет исходные роли и порядок.

## Смысл результата

HTTP 201 означает, что запрос принят, а квитанция сохранена. FAILED означает, что сервис
получил ошибку или не смог получить пригодный ответ в отведённое время. По тайм-ауту
нельзя установить, выполнил ли удалённый сервер запрос. Пересылки после ошибки нет.
Если запись конечного исхода в базу не подтвердилась, ответ содержит сохранённую квитанцию
и modelOutcomeSaved = false. При аварийном завершении процесса значение NOT_STARTED в базе
означает, что конечный исход не записан. Квитанция v1 подписывает принятые сообщения через
корень дерева и заявленную модель. Ответ и настройки генерации находятся вне области подписи.
