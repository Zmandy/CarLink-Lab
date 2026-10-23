using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace CarLinkDesktop {
    public static class Wire {
        public const uint Version = 0x18001, Match = 0x10002, HuInfo = 0x18003, MdInfo = 0x10004;
        public const uint Init = 0x18007, InitDone = 0x10008, Start = 0x18009, Pause = 0x1800A, Video = 0x20001;
        public static uint U32(byte[] b, int o) { return ((uint)b[o] << 24) | ((uint)b[o+1] << 16) | ((uint)b[o+2] << 8) | b[o+3]; }
        public static void Put32(byte[] b, int o, uint v) { for (int i=3;i>=0;i--) { b[o+i]=(byte)v; v >>= 8; } }
        public static byte[] Read(Stream s, int count, Func<bool> stopped) {
            if (count < 0 || count > 8*1024*1024) throw new InvalidDataException("Invalid packet size");
            byte[] b=new byte[count]; int offset=0;
            while(offset<count) {
                if(stopped()) throw new OperationCanceledException();
                int n=s.Read(b,offset,count-offset);
                if(n==0) throw new EndOfStreamException("Peer closed the channel");
                offset+=n;
            }
            return b;
        }
        public static void Command(Stream s, uint type, byte[] data) {
            data=data??new byte[0];
            if(data.Length>65535) throw new InvalidDataException("Command too large");
            byte[] h=new byte[8]; h[0]=(byte)(data.Length>>8); h[1]=(byte)data.Length; Put32(h,4,type);
            lock(s) { s.Write(h,0,h.Length); s.Write(data,0,data.Length); s.Flush(); }
        }
        public static uint ReadCommand(Stream s, out byte[] payload, Func<bool> stopped) {
            byte[] h=Read(s,8,stopped); payload=Read(s,(h[0]<<8)|h[1],stopped); return U32(h,4);
        }
        public static void VideoPacket(Stream s, byte[] data, int length, uint timestamp) {
            byte[] h=new byte[12]; Put32(h,0,(uint)length); Put32(h,4,timestamp); Put32(h,8,Video);
            s.Write(h,0,12); s.Write(data,0,length); s.Flush();
        }
        static void Varint(Stream s, uint value) { while(value>127) { s.WriteByte((byte)((value&127)|128)); value>>=7; } s.WriteByte((byte)value); }
        public static byte[] Fields(params uint[] values) {
            using(MemoryStream m=new MemoryStream()) { for(int i=0;i<values.Length;i++) { Varint(m,(uint)((i+1)<<3)); Varint(m,values[i]); } return m.ToArray(); }
        }
        public static uint[] ParseFields(byte[] b) {
            uint[] result=new uint[3]; int i=0;
            while(i<b.Length) { uint tag=ReadVarint(b,ref i); if((tag&7)!=0) throw new InvalidDataException("Expected varint"); uint v=ReadVarint(b,ref i); uint f=tag>>3; if(f>=1&&f<=3) result[f-1]=v; }
            return result;
        }
        static uint ReadVarint(byte[] b,ref int i) { uint v=0; for(int shift=0;shift<35;shift+=7) { if(i==b.Length) throw new InvalidDataException("Truncated protobuf"); byte x=b[i++]; if(shift==28&&(x&240)!=0) throw new InvalidDataException("Varint overflow"); v|=(uint)(x&127)<<shift; if((x&128)==0)return v; } throw new InvalidDataException("Varint overflow"); }
    }

    public sealed class Decoder : IDisposable {
        readonly Process process; readonly Action<Bitmap> frame; readonly Action<string> log;
        readonly int width,height; volatile bool closed;
        public long Frames;
        public Decoder(string ffmpeg,int w,int h,Action<Bitmap> onFrame,Action<string> onLog) {
            width=w; height=h; frame=onFrame; log=onLog;
            ProcessStartInfo info=new ProcessStartInfo(ffmpeg,
                "-hide_banner -loglevel warning -probesize 32 -analyzeduration 0 -flags low_delay -threads 2 -f h264 -i pipe:0 -an -vf scale="+w+":"+h+" -pix_fmt bgr24 -fps_mode passthrough -f rawvideo -flush_packets 1 pipe:1");
            info.UseShellExecute=false; info.CreateNoWindow=true; info.RedirectStandardInput=true; info.RedirectStandardOutput=true; info.RedirectStandardError=true;
            process=Process.Start(info);
            ThreadPool.QueueUserWorkItem(delegate { try { string line; while((line=process.StandardError.ReadLine())!=null) { if(!closed) log("解码器："+line); } } catch { } });
            ThreadPool.QueueUserWorkItem(delegate { ReadFrames(); });
        }
        public void Feed(byte[] bytes) { if(closed) return; process.StandardInput.BaseStream.Write(bytes,0,bytes.Length); process.StandardInput.BaseStream.Flush(); }
        void ReadFrames() {
            try {
                while(!closed) {
                    byte[] pixels=Wire.Read(process.StandardOutput.BaseStream,width*height*3,delegate { return closed; });
                    Bitmap image=new Bitmap(width,height,PixelFormat.Format24bppRgb);
                    BitmapData bits=image.LockBits(new Rectangle(0,0,width,height),ImageLockMode.WriteOnly,PixelFormat.Format24bppRgb);
                    try { for(int y=0;y<height;y++) Marshal.Copy(pixels,y*width*3,IntPtr.Add(bits.Scan0,y*bits.Stride),width*3); }
                    finally { image.UnlockBits(bits); }
                    Interlocked.Increment(ref Frames); frame(image);
                }
            } catch(Exception e) { if(!closed) log("视频解码结束："+e.Message); }
        }
        public void Dispose() {
            if(closed) return; closed=true;
            try { if(!process.HasExited) process.Kill(); } catch { }
            try { process.StandardInput.Close(); } catch { }
        }
    }

    public sealed class Session : IDisposable {
        readonly string root, ffmpeg; readonly Action<string> log; readonly Action<Bitmap> frame;
        readonly List<TcpClient> sockets=new List<TcpClient>(); readonly object sync=new object();
        readonly List<int> forwards=new List<int>();
        readonly Stopwatch clock=Stopwatch.StartNew(); readonly bool record;
        TcpClient control,video; Decoder decoder; FileStream capture; StreamWriter journal;
        int stopped; string serial; public long Packets,Bytes; public uint LastTimestamp;
        public long Frames { get { return decoder==null?0:Interlocked.Read(ref decoder.Frames); } }
        public double Seconds { get { return clock.Elapsed.TotalSeconds; } }
        public string Folder { get; private set; } public volatile bool Ready,Paused;
        public Session(string baseDir,string decoderPath,bool save,Action<string> onLog,Action<Bitmap> onFrame) {
            root=baseDir; ffmpeg=decoderPath; record=save; log=onLog; frame=onFrame;
            Folder=Path.Combine(root,"logs",DateTime.Now.ToString("yyyyMMdd-HHmmss-fff")); Directory.CreateDirectory(Folder);
            journal=new StreamWriter(Path.Combine(Folder,"session.log"),false,new UTF8Encoding(false)); journal.AutoFlush=true;
        }
        public bool Stopped { get { return Interlocked.CompareExchange(ref stopped,0,0)!=0; } }
        void Log(string text) { lock(sync) { if(journal!=null) journal.WriteLine(DateTime.Now.ToString("HH:mm:ss.fff")+" "+text); } log(text); }
        TcpClient Open(int port) {
            if(Stopped) throw new OperationCanceledException();
            TcpClient c=new TcpClient(); c.NoDelay=true;
            IAsyncResult pending=c.BeginConnect(IPAddress.Loopback,port,null,null);
            if(!pending.AsyncWaitHandle.WaitOne(6000)) { c.Close(); throw new TimeoutException("连接端口超时："+port); }
            c.EndConnect(pending); lock(sync) { if(Stopped) { c.Close(); throw new OperationCanceledException(); } sockets.Add(c); } return c;
        }
        public void ConnectPhone(string target,int width,int height,int fps) {
            serial=target;
            Log("通过 ADB 连接 "+serial+"；请先在 APP 中开始连接。");
            int[] ports={7240,8240,9240,9241,9242,9340}; int[] local=new int[6];
            for(int i=0;i<ports.Length;i++) {
                string value=Adb(root,"-s "+Quote(serial)+" forward tcp:0 tcp:"+ports[i]).Trim();
                int p; if(!Int32.TryParse(value,out p)) throw new IOException("无法取得 ADB 转发端口："+value);
                lock(sync) {
                    if(Stopped) { Adb(root,"-s "+Quote(serial)+" forward --remove tcp:"+p); throw new OperationCanceledException(); }
                    forwards.Add(p);
                } local[i]=p;
            }
            control=Open(local[0]); video=Open(local[1]);
            for(int i=2;i<local.Length;i++) Open(local[i]);
            Handshake(width,height,fps,"APP / "+serial);
        }
        public void ConnectDemo(int commandPort,int videoPort,int width,int height,int fps) {
            Log("本地自检：测试图来自电脑 FFmpeg，不是手机 APP。");
            control=Open(commandPort); video=Open(videoPort); Handshake(width,height,fps,"本地自检");
        }
        void Exchange(uint request,byte[] bytes,uint expected) {
            NetworkStream stream=control.GetStream(); stream.ReadTimeout=6000; Wire.Command(stream,request,bytes);
            for(int i=0;i<16;i++) { byte[] response; uint type=Wire.ReadCommand(stream,out response,delegate { return Stopped; }); Log("RX 控制 0x"+type.ToString("X8")+"，"+response.Length+" 字节"); if(type==expected) { if(expected==Wire.Match&&(response.Length<2||response[0]!=8||response[1]!=1)) throw new IOException("APP 拒绝协议版本"); return; } }
            throw new IOException("未收到预期握手响应");
        }
        void Handshake(int width,int height,int fps,string source) {
            if(width<160||width>1920||height<120||height>1080||(width%2)!=0||(height%2)!=0||fps<1||fps>30) throw new ArgumentException("无效的视频参数");
            Exchange(Wire.Version,Wire.Fields(2,0),Wire.Match);
            Exchange(Wire.HuInfo,new byte[0],Wire.MdInfo);
            Exchange(Wire.Init,Wire.Fields((uint)width,(uint)height,(uint)fps),Wire.InitDone);
            lock(sync) {
                if(Stopped) throw new OperationCanceledException();
                decoder=new Decoder(ffmpeg,width,height,frame,Log);
                if(record) capture=File.Create(Path.Combine(Folder,"video.h264"));
            }
            Log("视频协商完成："+width+"x"+height+"@"+fps+"；来源："+source);
            control.GetStream().ReadTimeout=System.Threading.Timeout.Infinite;
            ThreadPool.QueueUserWorkItem(delegate { ReceiveVideo(); });
            ThreadPool.QueueUserWorkItem(delegate { ReadControl(); });
            Wire.Command(control.GetStream(),Wire.Start,null); Ready=true; Log("已发送开始视频，等待解码首帧。");
        }
        void ReadControl() { try { while(!Stopped) { byte[] p; uint t=Wire.ReadCommand(control.GetStream(),out p,delegate { return Stopped; }); Log("RX 控制 0x"+t.ToString("X8")+"，"+p.Length+" 字节"); } } catch(Exception e) { if(!Stopped) { Log("控制通道断开："+e.Message); Dispose(); } } }
        void ReceiveVideo() {
            try {
                Stream stream=video.GetStream();
                while(!Stopped) {
                    byte[] header=Wire.Read(stream,12,delegate { return Stopped; }); uint size=Wire.U32(header,0);
                    if(size==0||size>8*1024*1024||Wire.U32(header,8)!=Wire.Video) throw new InvalidDataException("无效的 CarLife 视频消息");
                    byte[] data=Wire.Read(stream,(int)size,delegate { return Stopped; }); LastTimestamp=Wire.U32(header,4);
                    Interlocked.Increment(ref Packets); Interlocked.Add(ref Bytes,data.Length);
                    if(capture!=null) capture.Write(data,0,data.Length);
                    decoder.Feed(data);
                }
            } catch(Exception e) { if(!Stopped) { Log("视频通道停止："+e.Message); Dispose(); } }
            finally { lock(sync) { if(capture!=null) { capture.Dispose(); capture=null; } } }
        }
        public void PauseVideo(bool pause) { if(!Ready||Stopped) return; Wire.Command(control.GetStream(),pause?Wire.Pause:Wire.Start,null); Paused=pause; Log(pause?"已发送暂停。若正在投屏，APP 会结束共享会话。":"已发送继续。APP 默认恢复仪表，投屏需重新授权。"); }
        public void Dispose() {
            if(Interlocked.Exchange(ref stopped,1)!=0) return; Ready=false;
            try { if(control!=null) Wire.Command(control.GetStream(),Wire.Pause,null); } catch { }
            int[] allocated;
            lock(sync) { if(decoder!=null)decoder.Dispose(); foreach(TcpClient c in sockets) try { c.Close(); } catch { } sockets.Clear(); allocated=forwards.ToArray(); forwards.Clear(); }
            if(serial!=null) foreach(int p in allocated) try { Adb(root,"-s "+Quote(serial)+" forward --remove tcp:"+p); } catch { }
            lock(sync) { if(journal!=null) { journal.WriteLine("STOP packets="+Packets+" bytes="+Bytes+" decoded="+Frames); journal.Dispose(); journal=null; } }
        }
        public static string Quote(string value) { return "\""+value.Replace("\"","\\\"")+"\""; }
        public static string Adb(string root,string arguments) {
            string exe=Path.Combine(root,"tools","adb.exe");
            ProcessStartInfo info=new ProcessStartInfo(exe,arguments); info.UseShellExecute=false; info.CreateNoWindow=true; info.RedirectStandardOutput=true; info.RedirectStandardError=true;
            using(Process p=Process.Start(info)) {
                Task<string> stdout=Task.Factory.StartNew(delegate { return p.StandardOutput.ReadToEnd(); });
                Task<string> stderr=Task.Factory.StartNew(delegate { return p.StandardError.ReadToEnd(); });
                if(!p.WaitForExit(20000)) { try { p.Kill(); } catch { } throw new TimeoutException("ADB 超时，请检查手机授权和连接。"); }
                string output=stdout.Result, error=stderr.Result; if(p.ExitCode!=0) throw new IOException("ADB："+error+output); return output;
            }
        }
    }

    // A synthetic phone for desktop-only decoder/protocol regression. No navigation is simulated.
    public sealed class DemoPhone : IDisposable {
        readonly TcpListener commands=new TcpListener(IPAddress.Loopback,0), videos=new TcpListener(IPAddress.Loopback,0);
        readonly string ffmpeg; readonly object gate=new object(); TcpClient command,video; Process encoder; volatile bool stopped;
        uint width=800,height=480,fps=15;
        public int CommandPort { get { return ((IPEndPoint)commands.LocalEndpoint).Port; } }
        public int VideoPort { get { return ((IPEndPoint)videos.LocalEndpoint).Port; } }
        public DemoPhone(string path) { ffmpeg=path; commands.Start(); videos.Start(); ThreadPool.QueueUserWorkItem(delegate { Serve(); }); }
        void Serve() {
            try {
                command=commands.AcceptTcpClient(); video=videos.AcceptTcpClient(); Stream s=command.GetStream();
                while(!stopped) {
                    byte[] p; uint t=Wire.ReadCommand(s,out p,delegate { return stopped; });
                    if(t==Wire.Version) Wire.Command(s,Wire.Match,Wire.Fields(1));
                    else if(t==Wire.HuInfo) Wire.Command(s,Wire.MdInfo,new byte[0]);
                    else if(t==Wire.Init) { uint[] v=Wire.ParseFields(p); width=v[0]; height=v[1]; fps=v[2]; Wire.Command(s,Wire.InitDone,p); }
                    else if(t==Wire.Start) StartEncoder();
                    else if(t==Wire.Pause) StopEncoder();
                }
            } catch { } finally { StopEncoder(); }
        }
        void StartEncoder() {
            lock(gate) {
                if(stopped) return;
                if(encoder!=null&&!encoder.HasExited) return;
                ProcessStartInfo info=new ProcessStartInfo(ffmpeg,"-hide_banner -loglevel error -re -f lavfi -i testsrc2=size="+width+"x"+height+":rate="+fps+" -an -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g "+fps+" -bf 0 -f h264 -flush_packets 1 pipe:1");
                info.UseShellExecute=false; info.CreateNoWindow=true; info.RedirectStandardOutput=true; info.RedirectStandardError=true;
                Process active=Process.Start(info); encoder=active;
                ThreadPool.QueueUserWorkItem(delegate { try { active.StandardError.ReadToEnd(); } catch { } });
                ThreadPool.QueueUserWorkItem(delegate {
                    byte[] b=new byte[65536]; try { int n; while(!stopped&&(n=active.StandardOutput.BaseStream.Read(b,0,b.Length))>0) { lock(video) Wire.VideoPacket(video.GetStream(),b,n,unchecked((uint)Environment.TickCount)); } } catch { }
                });
            }
        }
        void StopEncoder() { lock(gate) { if(encoder!=null) { try { if(!encoder.HasExited) encoder.Kill(); } catch { } encoder=null; } } }
        public void Dispose() { stopped=true; StopEncoder(); try { commands.Stop(); videos.Stop(); if(command!=null)command.Close(); if(video!=null)video.Close(); } catch { } }
    }

    public sealed class HeadUnitForm : Form {
        readonly string root=AppDomain.CurrentDomain.BaseDirectory, ffmpeg;
        readonly ComboBox device=new ComboBox(), resolution=new ComboBox(), fps=new ComboBox();
        readonly PictureBox picture=new PictureBox(); readonly TextBox logs=new TextBox();
        readonly Label status=new Label(), stats=new Label(); readonly CheckBox record=new CheckBox();
        readonly Button connect=new Button(), demoButton=new Button(), refresh=new Button(), pause=new Button(), stop=new Button(), shot=new Button();
        readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
        readonly object frameGate=new object(); Bitmap pending; Session session; DemoPhone demo; bool busy,closing; long lastFrames; double lastSeconds;
        public HeadUnitForm() {
            ffmpeg=Path.Combine(root,"tools","ffmpeg.exe"); Text="CarLink 电脑模拟车机 · 0.1.0"; Font=new Font("Microsoft YaHei UI",9F); Width=1150; Height=900; MinimumSize=new Size(900,730); StartPosition=FormStartPosition.CenterScreen;
            BackColor=Color.FromArgb(239,243,249);
            TableLayoutPanel grid=new TableLayoutPanel(); grid.Dock=DockStyle.Fill; grid.Padding=new Padding(16); grid.ColumnCount=1; grid.RowCount=6;
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,34)); grid.RowStyles.Add(new RowStyle(SizeType.Absolute,46)); grid.RowStyles.Add(new RowStyle(SizeType.Percent,100)); grid.RowStyles.Add(new RowStyle(SizeType.Absolute,42)); grid.RowStyles.Add(new RowStyle(SizeType.Absolute,30)); grid.RowStyles.Add(new RowStyle(SizeType.Absolute,166)); Controls.Add(grid);
            Label title=new Label(); title.Text="CarLife 接收与视频调试  /  先在 APP 点击“开始连接”"; title.AutoSize=true; title.Font=new Font(Font.FontFamily,12F,FontStyle.Bold); grid.Controls.Add(title,0,0);
            FlowLayoutPanel tools=new FlowLayoutPanel(); tools.Dock=DockStyle.Fill; tools.WrapContents=false;
            device.Width=235; device.DropDownStyle=ComboBoxStyle.DropDown; device.AccessibleName="ADB 设备";
            resolution.DropDownStyle=ComboBoxStyle.DropDownList; resolution.Width=120; resolution.Items.AddRange(new object[]{"800 x 480","1024 x 600","1280 x 720"}); resolution.SelectedIndex=0; resolution.AccessibleName="分辨率";
            fps.DropDownStyle=ComboBoxStyle.DropDownList; fps.Width=60; fps.Items.AddRange(new object[]{"10","15","20","30"}); fps.SelectedIndex=1; fps.AccessibleName="帧率";
            Setup(refresh,"刷新设备",90); Setup(connect,"连接 APP",100); Setup(demoButton,"本地自检",100);
            tools.Controls.Add(device); tools.Controls.Add(refresh); tools.Controls.Add(resolution); tools.Controls.Add(fps); tools.Controls.Add(connect); tools.Controls.Add(demoButton); grid.Controls.Add(tools,0,1);
            picture.Dock=DockStyle.Fill; picture.BackColor=Color.FromArgb(13,23,39); picture.SizeMode=PictureBoxSizeMode.Zoom; picture.AccessibleName="收到并解码的车机画面"; grid.Controls.Add(picture,0,2);
            FlowLayoutPanel actions=new FlowLayoutPanel(); actions.Dock=DockStyle.Fill;
            Setup(pause,"暂停视频",100); Setup(stop,"断开连接",100); Setup(shot,"保存截图",100); Button folder=new Button(); Setup(folder,"打开日志目录",130);
            record.Text="保存 H.264"; record.AutoSize=true; record.Checked=true; record.Margin=new Padding(16,9,0,0);
            actions.Controls.Add(pause); actions.Controls.Add(stop); actions.Controls.Add(shot); actions.Controls.Add(folder); actions.Controls.Add(record); grid.Controls.Add(actions,0,3);
            FlowLayoutPanel readings=new FlowLayoutPanel(); readings.Dock=DockStyle.Fill; readings.WrapContents=false;
            status.AutoSize=true; status.Text="未连接 · 本地自检无需手机"; status.Margin=new Padding(0,4,20,0); stats.AutoSize=true; stats.Margin=new Padding(0,4,0,0); readings.Controls.Add(status); readings.Controls.Add(stats); grid.Controls.Add(readings,0,4);
            logs.Dock=DockStyle.Fill; logs.Multiline=true; logs.ReadOnly=true; logs.ScrollBars=ScrollBars.Vertical; logs.Font=new Font("Consolas",9F); grid.Controls.Add(logs,0,5);
            refresh.Click+=delegate { RefreshDevices(); }; connect.Click+=delegate { BeginSession(false); }; demoButton.Click+=delegate { BeginSession(true); };
            pause.Click+=delegate { Session s=session; if(s!=null) Task.Factory.StartNew(delegate { try { s.PauseVideo(!s.Paused); } catch(Exception e) { Log(e.Message); } }); };
            stop.Click+=delegate { Disconnect(); }; shot.Click+=delegate { SaveShot(); };
            folder.Click+=delegate { Directory.CreateDirectory(Path.Combine(root,"logs")); Process.Start(new ProcessStartInfo("explorer.exe",Session.Quote(Path.Combine(root,"logs"))){UseShellExecute=true}); };
            timer.Interval=33; timer.Tick+=delegate { TickFrame(); }; timer.Start();
            Shown+=delegate { RefreshDevices(); }; FormClosing+=delegate { closing=true; timer.Stop(); if(session!=null)session.Dispose(); if(demo!=null)demo.Dispose(); lock(frameGate) { if(pending!=null)pending.Dispose(); } if(picture.Image!=null)picture.Image.Dispose(); };
            UpdateButtons();
        }
        static void Setup(Button b,string text,int width) { b.Text=text; b.Width=width; b.Height=32; b.Margin=new Padding(0,2,8,2); }
        void Log(string line) {
            if(closing||IsDisposed) return;
            try { BeginInvoke((Action)delegate { if(logs.TextLength>60000) logs.Text=logs.Text.Substring(logs.TextLength-30000); logs.AppendText(DateTime.Now.ToString("HH:mm:ss")+" "+line+Environment.NewLine); }); } catch { }
        }
        void OnFrame(Bitmap bitmap) { lock(frameGate) { if(closing) { bitmap.Dispose(); return; } if(pending!=null)pending.Dispose(); pending=bitmap; } }
        void TickFrame() {
            lock(frameGate) { if(pending!=null) { Image old=picture.Image; picture.Image=pending; pending=null; if(old!=null)old.Dispose(); } }
            Session s=session;
            if(s!=null) {
                long n=s.Frames; double elapsed=s.Seconds; double rate=elapsed>lastSeconds?(n-lastFrames)/(elapsed-lastSeconds):0;
                if(elapsed-lastSeconds>=1) { stats.Text=String.Format("解码 {0} 帧  |  {1:F1} fps  |  {2:F2} Mbps  |  {3} 视频包",n,rate,s.Bytes*8/Math.Max(1,elapsed)/1000000,s.Packets); lastFrames=n; lastSeconds=elapsed; }
                status.Text=s.Stopped?"连接已断开，可重新连接":busy?"连接与协商中…":s.Paused?"视频已暂停":n==0?"已协商，等待解码首帧…":demo!=null?"本地自检图 · 非手机画面":"APP 实际画面 · 接收中";
                pause.Text=s.Paused?"继续视频":"暂停视频";
            }
            UpdateButtons();
        }
        void UpdateButtons() { bool running=session!=null&&!session.Stopped; connect.Enabled=!busy&&!running; demoButton.Enabled=!busy&&!running; refresh.Enabled=!busy&&!running; device.Enabled=!busy&&!running; resolution.Enabled=!busy&&!running; fps.Enabled=!busy&&!running; record.Enabled=!busy&&!running; pause.Enabled=!busy&&running&&session.Ready; stop.Enabled=!busy&&running; shot.Enabled=picture.Image!=null; }
        void RefreshDevices() {
            if(busy) return; busy=true; UpdateButtons();
            Task.Factory.StartNew(delegate {
                try { string output=Session.Adb(root,"devices -l"); List<string> found=Checks.DeviceIds(output);
                    BeginInvoke((Action)delegate { string previous=device.Text; device.Items.Clear(); foreach(string id in found)device.Items.Add(id); if(device.Items.Count>0)device.SelectedIndex=0; if(found.Contains(previous))device.Text=previous; busy=false; UpdateButtons(); });
                    Log(found.Count==0?"未发现设备。可先运行本地自检；手机或 Android 模拟器需通过 ADB 连接。":"已发现 "+found.Count+" 个 ADB 连接；同一手机可能有 IP 和 mDNS 两个名称。");
                } catch(Exception e) { Log(e.Message); try { BeginInvoke((Action)delegate { busy=false; UpdateButtons(); }); } catch { } }
            });
        }
        void BeginSession(bool local) {
            if(busy) return;
            if(!File.Exists(ffmpeg)) { MessageBox.Show("缺少 tools/ffmpeg.exe，请完整解压工具包。"); return; }
            string serial=device.Text.Trim(); if(!local&&serial.Length==0) { MessageBox.Show("请先连接手机或 Android 模拟器并刷新设备。"); return; }
            string[] parts=resolution.Text.Split('x'); int w=Int32.Parse(parts[0]),h=Int32.Parse(parts[1]),rate=Int32.Parse(fps.Text); bool save=record.Checked;
            busy=true; status.Text="连接与协商中…"; UpdateButtons();
            Task.Factory.StartNew(delegate {
                try {
                    if(session!=null)session.Dispose(); if(demo!=null)demo.Dispose(); demo=null;
                    Session active=new Session(root,ffmpeg,save,Log,OnFrame); session=active; lastFrames=0; lastSeconds=0;
                    if(local) { demo=new DemoPhone(ffmpeg); active.ConnectDemo(demo.CommandPort,demo.VideoPort,w,h,rate); }
                    else active.ConnectPhone(serial,w,h,rate);
                } catch(Exception e) { Log("连接失败："+e.Message+"。确认 APP 已开始连接，且当前未被真实车机占用。"); if(session!=null)session.Dispose(); }
                finally { if(!closing) try { BeginInvoke((Action)delegate { busy=false; UpdateButtons(); }); } catch { } }
            });
        }
        void Disconnect() {
            busy=true; UpdateButtons(); Task.Factory.StartNew(delegate { if(session!=null)session.Dispose(); if(demo!=null)demo.Dispose(); demo=null; if(!closing)try { BeginInvoke((Action)delegate { busy=false; status.Text="已断开"; UpdateButtons(); }); } catch { } });
        }
        void SaveShot() { if(picture.Image==null)return; string folder=session==null?Path.Combine(root,"logs"):session.Folder; Directory.CreateDirectory(folder); string path=Path.Combine(folder,"screen-"+DateTime.Now.ToString("HHmmss-fff")+".png"); picture.Image.Save(path,ImageFormat.Png); Log("已保存截图："+path); }
    }

    public static class Program {
        [STAThread] public static int Main(string[] args) {
            try {
                if(args.Length>0&&args[0]=="--self-test") return Checks.Run(AppDomain.CurrentDomain.BaseDirectory);
                if(args.Length>1&&args[0]=="--phone-test") return Checks.Phone(AppDomain.CurrentDomain.BaseDirectory,args[1]);
                Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new HeadUnitForm()); return 0;
            } catch(Exception e) { string path=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"startup-error.txt"); File.WriteAllText(path,e.ToString()); if(args.Length==0)MessageBox.Show(e.Message,"CarLink 模拟车机"); return 1; }
        }
    }

    public static class Checks {
        public static List<string> DeviceIds(string output) {
            List<string> ids=new List<string>();
            foreach(string line in output.Split('\n')) {
                System.Text.RegularExpressions.Match match=System.Text.RegularExpressions.Regex.Match(line.Trim(),@"^(.+?)\s+device(?:\s|$)");
                if(match.Success) ids.Add(match.Groups[1].Value);
            }
            return ids;
        }
        static void Require(bool condition,string message) { if(!condition) throw new Exception("CHECK FAILED: "+message); }
        static bool Wait(Func<bool> predicate,int milliseconds) { Stopwatch time=Stopwatch.StartNew(); while(time.ElapsedMilliseconds<milliseconds) { if(predicate())return true; Thread.Sleep(50); } return false; }
        public static int Run(string root) {
            string report=Path.Combine(root,"self-test.txt"); using(StreamWriter log=new StreamWriter(report,false,Encoding.UTF8)) {
                Action<string> emit=delegate(string s) { lock(log) { log.WriteLine(s); log.Flush(); } };
                using(MemoryStream m=new MemoryStream()) { Wire.Command(m,Wire.Init,Wire.Fields(800,480,15)); byte[] raw=m.ToArray(); Require(BitConverter.ToString(raw)=="00-08-00-00-00-01-80-07-08-A0-06-10-E0-03-18-0F","golden command packet"); m.Position=0; byte[] p; Require(Wire.ReadCommand(m,out p,delegate { return false; })==Wire.Init,"command type"); uint[] v=Wire.ParseFields(p); Require(v[0]==800&&v[1]==480&&v[2]==15,"protobuf values"); }
                emit("PASS golden big-endian header and protobuf negotiation");
                List<string> ids=DeviceIds("List of devices attached\n192.168.1.2:12345  device product:x\nadb-phone (2)._adb-tls-connect._tcp\tdevice product:x\nunauthorized-phone\tunauthorized\nemulator-5554\tdevice\n");
                Require(ids.Count==3&&ids[1]=="adb-phone (2)._adb-tls-connect._tcp"&&ids[2]=="emulator-5554","ADB devices whitespace parsing");
                emit("PASS ADB device list spaces / tabs / mDNS names / emulator / unauthorized filtering");
                try { Wire.Read(new MemoryStream(new byte[1]),8,delegate { return false; }); throw new Exception("EOF not rejected"); } catch(EndOfStreamException) { }
                try { Wire.Read(new MemoryStream(),8*1024*1024+1,delegate { return false; }); throw new Exception("Size not bounded"); } catch(InvalidDataException) { }
                emit("PASS truncated packet and oversized packet rejection");
                string ffmpeg=Path.Combine(root,"tools","ffmpeg.exe");
                foreach(int width in new int[]{800,1024}) {
                    using(DemoPhone phone=new DemoPhone(ffmpeg)) using(Session session=new Session(root,ffmpeg,true,emit,delegate(Bitmap b) { b.Dispose(); })) {
                        session.ConnectDemo(phone.CommandPort,phone.VideoPort,width,width==800?480:600,15);
                        Require(Wait(delegate { return session.Frames>=12; },15000),"decode demo frames");
                        session.PauseVideo(true); Thread.Sleep(1200); long paused=session.Frames; Thread.Sleep(500); Require(session.Frames<=paused+2,"pause stops video");
                        session.PauseVideo(false); Require(Wait(delegate { return session.Frames>=paused+8; },10000),"resume decodes new keyframes");
                        emit("PASS "+width+"px handshake / decode / pause / resume; frames="+session.Frames);
                    }
                }
                emit("ALL SELF TESTS PASSED");
            }
            return 0;
        }
        public static int Phone(string root,string serial) {
            using(StreamWriter log=new StreamWriter(Path.Combine(root,"phone-test.txt"),false,Encoding.UTF8)) {
                Action<string> emit=delegate(string s) { lock(log) { log.WriteLine(s); log.Flush(); } };
                string ffmpeg=Path.Combine(root,"tools","ffmpeg.exe");
                for(int round=0;round<2;round++) {
                    bool saved=false; int decoded=0;
                    using(Session session=new Session(root,ffmpeg,true,emit,delegate(Bitmap b) { lock(log) { if(++decoded>=12&&!saved) { b.Save(Path.Combine(root,"phone-frame-"+round+".png"),ImageFormat.Png); saved=true; } } b.Dispose(); })) {
                        session.ConnectPhone(serial,800,480,15); Require(Wait(delegate { return session.Frames>=12; },20000),"phone decode");
                        session.PauseVideo(true); Thread.Sleep(1200); long n=session.Frames; Thread.Sleep(500); Require(session.Frames<=n+2,"phone pause");
                        session.PauseVideo(false); Require(Wait(delegate { return session.Frames>=n+8; },15000),"phone resume"); emit("PASS phone round "+round+" frames="+session.Frames);
                    }
                    Thread.Sleep(800);
                }
                emit("PHONE HANDSHAKE / VIDEO / PAUSE / RESUME / RECONNECT PASSED");
            }
            return 0;
        }
    }
}
