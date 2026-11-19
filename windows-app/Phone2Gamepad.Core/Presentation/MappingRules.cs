namespace Phone2Gamepad.Core.Presentation;

/// <summary>Editor-level mapping checks that look at every row (including unchanged ones), not just the stored overrides.</summary>
public static class MappingRules
{
    /// <summary>One message per Xbox button that more than one phone button would send.</summary>
    public static IReadOnlyList<string> DuplicateProblems(IEnumerable<(GamepadButtons Source, GamepadButtons Target)> rows) =>
        rows.GroupBy(r => r.Target).Where(g => g.Count() > 1).Select(g =>
            $"{string.Join(" and ", g.Select(r => UiFormat.ButtonShort(r.Source)))} both send {UiFormat.ButtonShort(g.Key)}. " +
            "Change one of them (to swap two buttons, map each to the other).").ToList();
}
