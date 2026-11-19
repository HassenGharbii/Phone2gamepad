using System.Collections.Specialized;
using System.Windows.Controls;

namespace Phone2Gamepad.Windows.Views;

public partial class DiagnosticsView : UserControl
{
    public DiagnosticsView()
    {
        InitializeComponent();
        // Follow new entries while the newest line is visible; leave the view alone when the user scrolled back.
        ((INotifyCollectionChanged)LogList.Items).CollectionChanged += (_, e) =>
        {
            if (e.Action != NotifyCollectionChangedAction.Add || LogList.Items.Count == 0) return;
            var sv = FindScroll(LogList);
            if (sv == null || sv.VerticalOffset >= sv.ScrollableHeight - 2) LogList.ScrollIntoView(LogList.Items[^1]);
        };
        Loaded += (_, _) => { if (LogList.Items.Count > 0) LogList.ScrollIntoView(LogList.Items[^1]); };
    }

    private static ScrollViewer? FindScroll(System.Windows.DependencyObject d)
    {
        for (int i = 0; i < System.Windows.Media.VisualTreeHelper.GetChildrenCount(d); i++)
        {
            var c = System.Windows.Media.VisualTreeHelper.GetChild(d, i);
            if (c is ScrollViewer sv) return sv;
            if (FindScroll(c) is { } r) return r;
        }
        return null;
    }
}
