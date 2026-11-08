"""Package a verified prebuilt game; never copy accounts, worlds or Minecraft assets."""
from pathlib import Path
import hashlib,json,shutil,zipfile

root=Path(__file__).resolve().parent.parent
build=root/'build/horizon'
source=build/'client-6.1-target/fast/horizon-client.exe'
dest=build/'ready-client-6.1'
dest.mkdir(parents=True,exist_ok=True)
shutil.copyfile(source,dest/'horizon-client.exe')
for name in ['Install-UnknownMod.ps1','Install-UnknownMod.cmd']:
    shutil.copyfile(root/'horizon'/name,dest/name)
shutil.copyfile(root/'horizon/PRIVACY-CHECKS.md',dest/'PRIVACY-CHECKS.md')
for license,name in [(root/'LICENSE','LICENSE-UnknownMod'),(build/'client-mod-source/LICENSE','LICENSE-Horizon'),(build/'voice-bundles/horizon_voice/LICENSE','LICENSE-Horizon-Voice')]:
    shutil.copyfile(license,dest/name)
manifest={'version':'6.1.0','game':'26.3','horizon':'2ebf00a0281c592f79191e76fce0e7d522c6f425',
    'base_version':'nightly-2026-11-07-2ebf00a','sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
    'mods':['unknownmod_client 6.1.0','horizon_voice 0.3.3'],'profile':'native-development'}
(dest/'release.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
(dest/'README.txt').write_text('''UnknownMod 6.1 / Horizon 26.3 — готовый клиент для Windows x64

1. Установите официальный лаунчер Horizon.
2. Установите и подготовьте Nightly 2026-11-07 (2ebf00a) для Minecraft 26.3.
3. Распакуйте весь архив и откройте Install-UnknownMod.cmd.
4. Перезапустите лаунчер. Выберите импортированную версию.
5. Нажмите «Играть». Повторная нативная компиляция не требуется.

Меню UnknownMod: U (назначение можно изменить в управлении).
Голос: V — говорить; G — настройки голоса.

Моды уже встроены. Не запускайте поверх этой версии ещё одну нативную сборку
тех же модов. Для добавления сторонних нативных модов нужна отдельная сборка.
Импортированная версия закреплена: для обновления импортируйте новый архив.
Установщик не запускает игру и не переносит аккаунты, миры или игровые ресурсы.
Правила выполняет совместимый сервер UnknownMod 6.1.

Это сборка для тестов, профиль native-development. Известные границы
анонимности на уровне UUID описаны в приложенном PRIVACY-CHECKS.md.
''',encoding='utf-8')
archive=build/'packages/UnknownMod-6.1.0-Horizon-Windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as packed:
    for file in sorted(dest.iterdir()):
        if file.is_file():packed.write(file,file.name)
print(json.dumps({'file':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size}))
