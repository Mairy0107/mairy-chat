@echo off
cd /d "C:\Users\mrmai\Desktop\mairy-chat"
"C:\Users\mrmai\AppData\Local\OpenAI\Codex\bin\2993767bd1dfb009\codex.exe" exec -m gpt-6.1-sol -c model_reasoning_effort="high" -s danger-full-access --dangerously-bypass-approvals-and-sandbox -C "C:\Users\mrmai\Desktop\mairy-chat" -o "C:\Users\mrmai\Desktop\mairy-chat\codex-pin-composer-last.txt" --json < "C:\Users\mrmai\Desktop\mairy-chat\codex-pin-composer-prompt.txt" > "C:\Users\mrmai\Desktop\mairy-chat\codex-pin-composer.log" 2> "C:\Users\mrmai\Desktop\mairy-chat\codex-pin-composer.err.log"
echo EXITCODE=%ERRORLEVEL% > "C:\Users\mrmai\Desktop\mairy-chat\codex-pin-composer.exit"
