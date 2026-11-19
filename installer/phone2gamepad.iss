; Phone2Gamepad Windows installer (Inno Setup 6).
; STATUS: written but NOT compiled or tested (Inno Setup was not installed when this was authored).
;
; Build:  1) scripts\publish-windows.ps1        (creates dist\Phone2Gamepad-<ver>-win-x64-selfcontained)
;         2) iscc installer\phone2gamepad.iss   (add /DAppVersion=x.y.z if VERSION differs; output -> dist\)
;
; Design rules (from the product spec):
;   - per-user install, no administrator rights needed
;   - does NOT start with Windows, does NOT install the ViGEmBus driver, does NOT touch the firewall or registry
;     beyond Inno's own uninstall entry
;   - uninstall removes the app files and ASKS before deleting paired-device data

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#define AppName "Phone2Gamepad"
#define PublishDir "..\dist\Phone2Gamepad-" + AppVersion + "-win-x64-selfcontained"

[Setup]
AppId={{6C1B5E0A-2B84-4C61-9E0D-5D1F6B1E9A41}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Phone2Gamepad
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist
OutputBaseFilename=Phone2Gamepad-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\Phone2Gamepad.exe
; The app holds a single-instance mutex; ask Setup to wait for / close the running app.
AppMutex=Local\Phone2Gamepad.SingleInstance
CloseApplications=yes

[Tasks]
; Unchecked by default: nothing optional happens unless the user opts in.
Name: "desktopicon"; Description: "Create a &desktop shortcut"; Flags: unchecked
Name: "driverinfo"; Description: "Show the virtual controller driver (ViGEmBus) instructions after setup"; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\Phone2Gamepad.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\Phone2Gamepad.exe"; Tasks: desktopicon

[Run]
; Only when the user ticked the task. This just opens a text file; it installs nothing.
Filename: "{app}\README-first-run.txt"; Description: "Driver and first-run instructions"; Tasks: driverinfo; Flags: shellexec postinstall skipifsilent
Filename: "{app}\Phone2Gamepad.exe"; Description: "Launch {#AppName}"; Flags: nowait postinstall skipifsilent unchecked

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\Phone2Gamepad');
    if DirExists(DataDir) then
    begin
      // Default answer is No: pairings and settings are kept unless the user explicitly agrees.
      if MsgBox('Also delete Phone2Gamepad settings and paired-phone data?' + #13#10 + #13#10 +
                DataDir + #13#10 + #13#10 +
                'Phones will need to be paired again if you reinstall.',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
    end;
  end;
end;
