namespace Phone2Gamepad.Network;

/// <summary>A random GUID generated once per installation and persisted, so clients can tell two PCs with the same name apart.</summary>
public static class ServerIdentity
{
    public static string DefaultPath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Phone2Gamepad", "server-id.txt");

    /// <summary>Reads the id from <paramref name="path"/>; if missing or malformed, creates a new one and tries to persist it.</summary>
    public static string LoadOrCreate(string? path = null)
    {
        path ??= DefaultPath();
        try
        {
            if (File.Exists(path) && Guid.TryParse(File.ReadAllText(path).Trim(), out var existing) && existing != Guid.Empty)
                return existing.ToString("D");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* fall through: regenerate */ }

        var id = Guid.NewGuid().ToString("D");
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, id);
            File.Move(tmp, path, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* unwritable: the id is then per-run only */ }
        return id;
    }
}
