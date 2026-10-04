Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public class K {
    [DllImport("user32.dll")]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, IntPtr dwExtraInfo);
    public static void Tap(byte k) {
        keybd_event(k, 0, 1, IntPtr.Zero);
        Thread.Sleep(60);
        keybd_event(k, 0, 3, IntPtr.Zero);
        Thread.Sleep(60);
    }
    public static void Type(string s) {
        foreach (char c in s) {
            byte vk = (byte)c;
            keybd_event(vk, 0, 1, IntPtr.Zero);
            keybd_event(vk, 0, 3, IntPtr.Zero);
            Thread.Sleep(30);
        }
    }
    public static void CtrlA() {
        keybd_event(0x11, 0, 1, IntPtr.Zero);
        Thread.Sleep(50);
        keybd_event(0x41, 0, 1, IntPtr.Zero);
        Thread.Sleep(50);
        keybd_event(0x41, 0, 3, IntPtr.Zero);
        Thread.Sleep(50);
        keybd_event(0x11, 0, 3, IntPtr.Zero);
        Thread.Sleep(50);
    }
}
"@

[K]::Type("E:\True work gz\btmouse-staging")
Start-Sleep -Milliseconds 500
[K]::Tap(0x0D)
Start-Sleep -Milliseconds 1200
[K]::CtrlA()
Start-Sleep -Milliseconds 300
[K]::Tap(0x0D)
Write-Host "DONE"