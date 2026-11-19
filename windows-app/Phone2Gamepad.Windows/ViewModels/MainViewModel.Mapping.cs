using System.Collections.ObjectModel;
using System.Windows.Input;
using Phone2Gamepad.Core;
using Phone2Gamepad.Core.Presentation;

namespace Phone2Gamepad.Windows.ViewModels;

public sealed record ButtonChoice(GamepadButtons Value, string Name);

/// <summary>One "phone button → Xbox button" row.</summary>
public sealed class ButtonMapRow(MainViewModel owner, GamepadButtons source) : ObservableObject
{
    private GamepadButtons _target = source;
    private bool _duplicate;
    public GamepadButtons Source { get; } = source;
    public string SourceName => UiFormat.ButtonLong(Source);
    public string SourceShort => UiFormat.ButtonShort(Source);
    public GamepadButtons Target
    {
        get => _target;
        set { if (Set(ref _target, value)) { OnPropertyChanged(nameof(IsChanged)); owner.ApplyMapping(); } }
    }
    public bool IsChanged => Target != Source;
    public bool IsDuplicate { get => _duplicate; set => Set(ref _duplicate, value); }
}

public sealed partial class MainViewModel
{
    public static readonly GamepadButtons[] AllButtons =
    {
        GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y, GamepadButtons.LeftBumper, GamepadButtons.RightBumper,
        GamepadButtons.Back, GamepadButtons.Start, GamepadButtons.LeftStick, GamepadButtons.RightStick, GamepadButtons.Guide,
    };

    private bool _loadingProfile;
    private bool _invLX, _invLY, _invRX, _invRY, _ltDigital, _rtDigital;
    private double _leftDead, _rightDead, _leftSens = 1, _rightSens = 1, _trigMin, _trigMax = 255;
    private string _selectedProfile = "Default", _profileName = "", _mappingMessage = "";
    private bool _mappingOk = true;

    public ObservableCollection<ButtonMapRow> ButtonMap { get; } = new();
    public IReadOnlyList<ButtonChoice> ButtonChoices { get; } = AllButtons.Select(b => new ButtonChoice(b, UiFormat.ButtonLong(b))).ToList();
    public ObservableCollection<string> ProfileNames { get; } = new();

    public ICommand SaveProfileCommand { get; private set; } = null!;
    public ICommand DeleteProfileCommand { get; private set; } = null!;
    public ICommand RestoreDefaultsCommand { get; private set; } = null!;
    public ICommand ResetButtonCommand { get; private set; } = null!;

    private void InitMapping()
    {
        SaveProfileCommand = new RelayCommand(SaveProfile, () => ProfileName.Trim().Length > 0);
        DeleteProfileCommand = new RelayCommand(DeleteProfile, () => SelectedProfile != "Default");
        RestoreDefaultsCommand = new RelayCommand(RestoreDefaults);
        ResetButtonCommand = new RelayCommand(p => { if (p is ButtonMapRow r) r.Target = r.Source; });

        foreach (var b in AllButtons) ButtonMap.Add(new ButtonMapRow(this, b));
        ProfileNames.Add("Default");
        foreach (var p in _settings.Profiles) ProfileNames.Add(p.Name);
        _selectedProfile = ProfileNames.Contains(_settings.ActiveProfile) ? _settings.ActiveProfile : "Default";
        LoadProfile(FindProfile(_selectedProfile));
    }

    public bool InvertLeftX { get => _invLX; set { if (Set(ref _invLX, value)) ApplyMapping(); } }
    public bool InvertLeftY { get => _invLY; set { if (Set(ref _invLY, value)) ApplyMapping(); } }
    public bool InvertRightX { get => _invRX; set { if (Set(ref _invRX, value)) ApplyMapping(); } }
    public bool InvertRightY { get => _invRY; set { if (Set(ref _invRY, value)) ApplyMapping(); } }
    public bool LeftTriggerDigital { get => _ltDigital; set { if (Set(ref _ltDigital, value)) ApplyMapping(); } }
    public bool RightTriggerDigital { get => _rtDigital; set { if (Set(ref _rtDigital, value)) ApplyMapping(); } }
    public double LeftDeadZone { get => _leftDead; set { if (Set(ref _leftDead, Math.Round(value, 2))) ApplyMapping(); } }
    public double RightDeadZone { get => _rightDead; set { if (Set(ref _rightDead, Math.Round(value, 2))) ApplyMapping(); } }
    public double LeftSensitivity { get => _leftSens; set { if (Set(ref _leftSens, Math.Round(value, 2))) ApplyMapping(); } }
    public double RightSensitivity { get => _rightSens; set { if (Set(ref _rightSens, Math.Round(value, 2))) ApplyMapping(); } }
    public double TriggerMin { get => _trigMin; set { if (Set(ref _trigMin, Math.Round(value))) ApplyMapping(); } }
    public double TriggerMax { get => _trigMax; set { if (Set(ref _trigMax, Math.Round(value))) ApplyMapping(); } }
    public string MappingMessage { get => _mappingMessage; private set => Set(ref _mappingMessage, value); }
    public bool MappingOk { get => _mappingOk; private set => Set(ref _mappingOk, value); }
    public int ChangedButtons => ButtonMap.Count(r => r.IsChanged);
    public string ProfileName { get => _profileName; set { if (Set(ref _profileName, value)) CommandManager.InvalidateRequerySuggested(); } }
    public bool IsDefaultProfile => SelectedProfile == "Default";
    public string SelectedProfile
    {
        get => _selectedProfile;
        set
        {
            if (value == null || !Set(ref _selectedProfile, value)) return;
            LoadProfile(FindProfile(value));
            _settings.ActiveProfile = value; SaveSettings();
            OnPropertyChanged(nameof(IsDefaultProfile));
        }
    }

    private MappingProfile FindProfile(string name) => _settings.Profiles.FirstOrDefault(p => p.Name == name) ?? MappingProfile.CreateDefault();

    private void LoadProfile(MappingProfile p)
    {
        _loadingProfile = true;
        InvertLeftX = p.InvertLeftX; InvertLeftY = p.InvertLeftY; InvertRightX = p.InvertRightX; InvertRightY = p.InvertRightY;
        LeftDeadZone = p.LeftDeadZone; RightDeadZone = p.RightDeadZone; LeftSensitivity = p.LeftSensitivity; RightSensitivity = p.RightSensitivity;
        TriggerMin = p.TriggerMin; TriggerMax = p.TriggerMax; LeftTriggerDigital = p.LeftTriggerDigital; RightTriggerDigital = p.RightTriggerDigital;
        foreach (var row in ButtonMap) row.Target = p.ButtonMap.TryGetValue(row.Source, out var t) ? t : row.Source;
        _loadingProfile = false;
        ApplyMapping();
    }

    private MappingProfile BuildProfile(string name)
    {
        var p = new MappingProfile
        {
            Name = name, InvertLeftX = InvertLeftX, InvertLeftY = InvertLeftY, InvertRightX = InvertRightX, InvertRightY = InvertRightY,
            LeftDeadZone = LeftDeadZone, RightDeadZone = RightDeadZone, LeftSensitivity = LeftSensitivity, RightSensitivity = RightSensitivity,
            TriggerMin = (byte)Math.Clamp(TriggerMin, 0, 255), TriggerMax = (byte)Math.Clamp(TriggerMax, 0, 255),
            LeftTriggerDigital = LeftTriggerDigital, RightTriggerDigital = RightTriggerDigital,
        };
        foreach (var r in ButtonMap.Where(r => r.Target != r.Source)) p.ButtonMap[r.Source] = r.Target;
        return p;
    }

    /// <summary>Validates the editor state; applies it live only if valid. Invalid combinations are explained and highlighted, never applied.</summary>
    public void ApplyMapping()
    {
        if (_loadingProfile) return;
        var dupTargets = ButtonMap.GroupBy(r => r.Target).Where(g => g.Count() > 1).Select(g => g.Key).ToHashSet();
        foreach (var r in ButtonMap) r.IsDuplicate = dupTargets.Contains(r.Target);
        OnPropertyChanged(nameof(ChangedButtons));

        var p = BuildProfile(SelectedProfile);
        var problems = p.Validate().Concat(DuplicateProblems()).ToList();
        if (problems.Count == 0)
        {
            _host.Mapping = p;
            MappingOk = true;
            MappingMessage = SelectedProfile == "Default" && ChangedButtons == 0 && p.LeftDeadZone == 0 && p.RightDeadZone == 0
                ? "Standard Xbox mapping is active."
                : "Changes are applied to the controller as you make them.";
        }
        else
        {
            MappingOk = false;
            MappingMessage = "Not applied yet — " + string.Join(" ", problems.Distinct());
        }
    }

    /// <summary>Two phone buttons sending the same Xbox button would leave another button unreachable, so the editor refuses it.</summary>
    private IReadOnlyList<string> DuplicateProblems() => MappingRules.DuplicateProblems(ButtonMap.Select(r => (r.Source, r.Target)));

    private void SaveProfile()
    {
        var name = ProfileName.Trim();
        if (name.Equals("Default", StringComparison.OrdinalIgnoreCase)) { MappingOk = false; MappingMessage = "\"Default\" is built in. Choose another name."; return; }
        var p = BuildProfile(name);
        var problems = p.Validate().Concat(DuplicateProblems()).ToList();
        if (problems.Count > 0) { MappingOk = false; MappingMessage = "Can't save — " + string.Join(" ", problems); return; }
        _settings.Profiles.RemoveAll(x => x.Name == name);
        _settings.Profiles.Add(p);
        if (!ProfileNames.Contains(name)) ProfileNames.Add(name);
        _selectedProfile = name; OnPropertyChanged(nameof(SelectedProfile)); OnPropertyChanged(nameof(IsDefaultProfile));
        _settings.ActiveProfile = name; SaveSettings();
        _host.Mapping = p;
        ProfileName = "";
        MappingOk = true;
        MappingMessage = $"Saved as \"{name}\" and in use.";
    }

    private void DeleteProfile()
    {
        var name = SelectedProfile;
        if (name == "Default") return;
        if (Confirm?.Invoke(new DialogRequest("Delete profile?", $"\"{name}\" will be deleted. The standard mapping will be used instead.", "Delete", Destructive: true)) != true) return;
        _settings.Profiles.RemoveAll(x => x.Name == name);
        ProfileNames.Remove(name);
        SelectedProfile = "Default";
    }

    private void RestoreDefaults()
    {
        if (Confirm?.Invoke(new DialogRequest("Restore the standard mapping?",
                "All buttons go back to their Xbox defaults and stick/trigger adjustments are reset. Saved profiles are kept.", "Restore")) != true) return;
        SelectedProfile = "Default";
        LoadProfile(MappingProfile.CreateDefault());
    }
}
