#!/usr/bin/env python3
"""Prove the NIC evidence reader catches a query leak and rejects bad captures."""
import importlib.util, pathlib, struct, tempfile, unittest
spec=importlib.util.spec_from_file_location('capture',pathlib.Path(__file__).with_name('inspect-windows-udp-capture.py'))
capture=importlib.util.module_from_spec(spec);spec.loader.exec_module(capture)
def block(kind,body):
    body+=b'\0'*((-len(body))%4);size=len(body)+12
    return struct.pack('<II',kind,size)+body+struct.pack('<I',size)
def trace(linktype=1):
    dns=struct.pack('!HHHHHH',12345,0x100,1,0,0,0)+b'\7example\3com\0\0\1\0\1'
    udp=struct.pack('!HHHH',53123,53,len(dns)+8,0)+dns
    ip=bytes([0x45,0])+struct.pack('!H',20+len(udp))+b'\0'*4+bytes([64,17])+b'\0'*2+bytes([10,0,0,2,168,63,129,16])+udp
    ethernet=b'\0'*12+b'\x08\x00'+ip
    return (block(0x0a0d0d0a,struct.pack('<IHHq',0x1a2b3c4d,1,0,-1))+
            block(1,struct.pack('<HHI',linktype,0,65535))+
            block(6,struct.pack('<IIIII',0,0,0,len(ethernet),len(ethernet))+ethernet))
class CaptureTest(unittest.TestCase):
    def analyze(self,raw,identifier=12345):
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/'fixture.pcapng';p.write_bytes(raw)
            return capture.inspect(p,'168.63.129.16',identifier)
    def test_catches_real_dns_egress(self):self.assertEqual(1,self.analyze(trace())['matching_dns_query_frames'])
    def test_distinguishes_unique_phase_identifiers(self):self.assertEqual(0,self.analyze(trace(),54321)['matching_dns_query_frames'])
    def test_rejects_truncated_capture(self):
        with self.assertRaises(ValueError):self.analyze(trace()[:-1])
    def test_rejects_unknown_linktype_instead_of_false_negative(self):
        with self.assertRaises(ValueError):self.analyze(trace(999))
if __name__=='__main__':unittest.main()
