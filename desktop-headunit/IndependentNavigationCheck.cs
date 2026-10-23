// Integration check against a real, already paired Android phone running independent navigation mode.
// Build alongside CarLinkHeadUnit.exe, then pass receiver root, ADB serial and output directory.
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;
using CarLinkDesktop;

public static class IndependentNavigationCheck {
    static readonly object gate = new object();
    static Bitmap latest;
    static void Frame(Bitmap frame) {
        lock(gate) { if(latest!=null) latest.Dispose(); latest=frame; }
    }
    static Bitmap Save(string output, string name) {
        lock(gate) {
            if(latest==null) throw new Exception("No decoded frame");
            latest.Save(Path.Combine(output,name+".png"),ImageFormat.Png);
            return (Bitmap)latest.Clone();
        }
    }
    static void Advance(Session session, long previous, int milliseconds) {
        Thread.Sleep(milliseconds);
        if(session.Frames<previous+15 || session.Stopped) throw new Exception("Video stopped updating");
    }
    static void ClockChanged(Bitmap before, Bitmap after) {
        // Clock lies at the top left of the independently rendered 800x480 scene.
        int changed=0;
        for(int y=12;y<50;y++) for(int x=18;x<195;x++) {
            Color a=before.GetPixel(x,y), b=after.GetPixel(x,y);
            if(Math.Abs(a.R-b.R)+Math.Abs(a.G-b.G)+Math.Abs(a.B-b.B)>120) changed++;
        }
        if(changed<20) throw new Exception("Clock pixels did not change: "+changed);
        Console.WriteLine("PASS clock advanced: "+changed+" changed pixels");
    }
    public static int Main(string[] args) {
        if(args.Length!=3) { Console.Error.WriteLine("root serial output required"); return 2; }
        string root=args[0], serial=args[1], output=args[2];
        Directory.CreateDirectory(output);
        string target="-s "+Session.Quote(serial)+" ";
        try {
            using(Session session=new Session(root,Path.Combine(root,"tools","ffmpeg.exe"),false,Console.WriteLine,Frame)) {
                session.ConnectPhone(serial,800,480,15);
                Advance(session,0,6000);
                using(Bitmap first=Save(output,"foreground")) {
                    Session.Adb(root,target+"shell input keyevent KEYCODE_HOME");
                    long n=session.Frames; Advance(session,n,5000);
                    using(Bitmap home=Save(output,"phone-home")) ClockChanged(first,home);
                    Console.WriteLine("PASS HOME frames="+session.Frames);
                }
                File.WriteAllText(Path.Combine(output,"projection-state.txt"),Session.Adb(root,target+"shell dumpsys media_projection"));
                using(Bitmap before=Save(output,"before-sleep")) {
                    Session.Adb(root,target+"shell input keyevent KEYCODE_SLEEP");
                    long n=session.Frames; Advance(session,n,5000);
                    File.WriteAllText(Path.Combine(output,"power-state.txt"),Session.Adb(root,target+"shell dumpsys power"));
                    using(Bitmap off=Save(output,"screen-off")) ClockChanged(before,off);
                    Console.WriteLine("PASS screen-off frames="+session.Frames);
                }
                session.PauseVideo(true); Thread.Sleep(1500);
                long paused=session.Frames;
                session.PauseVideo(false); Advance(session,paused,5000);
                using(Bitmap resumed=Save(output,"resumed")) {}
                Console.WriteLine("PASS resume frames="+session.Frames);
            }
            return 0;
        } catch(Exception e) { Console.Error.WriteLine(e); return 1; }
        finally {
            try { Session.Adb(root,target+"shell input keyevent KEYCODE_WAKEUP"); } catch {}
            lock(gate) { if(latest!=null) latest.Dispose(); latest=null; }
        }
    }
}
