using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;

namespace Phone2Gamepad.Windows.Controls;

public enum ChecklistState { Pending, Done, Problem, Info }

/// <summary>One setup step: state glyph, title, description and optional actions (Content). Template lives in Themes/Styles.xaml.</summary>
public sealed class ChecklistRow : ContentControl
{
    public static readonly DependencyProperty StateProperty = DependencyProperty.Register(nameof(State), typeof(ChecklistState), typeof(ChecklistRow),
        new PropertyMetadata(ChecklistState.Pending, (d, _) => ((ChecklistRow)d).UpdateName()));
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(nameof(Title), typeof(string), typeof(ChecklistRow),
        new PropertyMetadata("", (d, _) => ((ChecklistRow)d).UpdateName()));
    public static readonly DependencyProperty DetailProperty = DependencyProperty.Register(nameof(Detail), typeof(string), typeof(ChecklistRow),
        new PropertyMetadata("", (d, _) => ((ChecklistRow)d).UpdateName()));

    public ChecklistState State { get => (ChecklistState)GetValue(StateProperty); set => SetValue(StateProperty, value); }
    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string Detail { get => (string)GetValue(DetailProperty); set => SetValue(DetailProperty, value); }


    public ChecklistRow() { Focusable = false; }

    private void UpdateName()
    {
        var state = State switch { ChecklistState.Done => "done", ChecklistState.Problem => "needs attention", ChecklistState.Info => "information", _ => "to do" };
        AutomationProperties.SetName(this, $"{Title}: {state}. {Detail}");
    }
}
