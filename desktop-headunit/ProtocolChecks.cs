using System;
using System.IO;
using System.Text;
using CarLinkDesktop;

public static class ProtocolChecks {
    sealed class Fragmented : MemoryStream {
        public Fragmented(byte[] b) : base(b) { }
        public override int Read(byte[] b,int offset,int count) { return base.Read(b,offset,Math.Min(count,2)); }
    }
    static void Check(bool ok,string label) { if(!ok) throw new Exception(label); Console.WriteLine("PASS "+label); }
    static void Reject<T>(Action action,string label) where T:Exception { try { action(); } catch(T) { Console.WriteLine("PASS "+label); return; } throw new Exception("Expected rejection: "+label); }
    public static int Main() {
        try {
            byte[] golden={0,8,0,0,0,1,128,7,8,160,6,16,224,3,24,15}; byte[] payload;
            using(Fragmented s=new Fragmented(golden)) { uint type=Wire.ReadCommand(s,out payload,delegate { return false; }); Check(type==Wire.Init&&Wire.ParseFields(payload)[0]==800,"fragmented command header and payload"); }
            Reject<EndOfStreamException>(delegate { byte[] p; Wire.ReadCommand(new Fragmented(new byte[]{0,8,0}),out p,delegate { return false; }); },"truncated command header");
            Reject<EndOfStreamException>(delegate { byte[] p; Wire.ReadCommand(new Fragmented(new byte[]{0,8,0,0,0,1,128,7,8}),out p,delegate { return false; }); },"truncated command body");
            Reject<InvalidDataException>(delegate { Wire.Command(new MemoryStream(),Wire.Init,new byte[65536]); },"command 16-bit length bound");
            using(MemoryStream s=new MemoryStream()) { Wire.VideoPacket(s,new byte[]{0,0,0,1,103},5,UInt32.MaxValue); Check(BitConverter.ToString(s.ToArray())=="00-00-00-05-FF-FF-FF-FF-00-02-00-01-00-00-00-01-67","golden video header and timestamp wrap"); }
            Reject<InvalidDataException>(delegate { Wire.Read(new MemoryStream(),8*1024*1024+1,delegate { return false; }); },"video allocation bound");
            Check(Wire.ParseFields(Wire.Fields(UInt32.MaxValue))[0]==UInt32.MaxValue,"protobuf maximum uint32");
            Reject<InvalidDataException>(delegate { Wire.ParseFields(new byte[]{8,255,255,255,255,127}); },"protobuf integer overflow");
            Reject<InvalidDataException>(delegate { Wire.ParseFields(new byte[]{8,128}); },"truncated protobuf varint");
            Reject<OperationCanceledException>(delegate { Wire.Read(new MemoryStream(new byte[8]),8,delegate { return true; }); },"cancel blocked frame before read");
            Console.WriteLine("ALL 10 PROTOCOL CHECKS PASSED"); return 0;
        } catch(Exception e) { Console.Error.WriteLine(e); return 1; }
    }
}
