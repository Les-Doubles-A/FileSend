; Installeur FileSend (Inno Setup 6). Installation par utilisateur, sans droits admin.
; Compilation : voir installer/build.ps1
#define AppName "FileSend"
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define AppExe "filesend-app.exe"

[Setup]
AppId={{B7C1D0A4-5E3F-4F7A-9A52-6D1E2F0C8A11}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=FileSend-Setup-{#AppVersion}
SetupIconFile=..\app\icons\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "startup"; Description: "Lancer FileSend au demarrage de Windows (dans la zone de notification)"; GroupDescription: "Options :"
Name: "desktopicon"; Description: "Creer un raccourci sur le Bureau"; GroupDescription: "Options :"; Flags: unchecked

[Files]
Source: "..\target\release\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
; Entree "Envoyer vers" (meme chemin que celui cree par l'application)
Name: "{userappdata}\Microsoft\Windows\SendTo\{#AppName}"; Filename: "{app}\{#AppExe}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"" --minimized"; Flags: uninsdeletevalue; Tasks: startup

[Run]
Filename: "{app}\{#AppExe}"; Description: "Lancer FileSend"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\{#AppExe}"; Parameters: "--remove-sendto"; Flags: runhidden; RunOnceId: "RemoveSendTo"
