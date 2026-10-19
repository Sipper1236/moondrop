#!/usr/bin/env python3
"""Count one fixture DNS query at NIC capture points; do not infer wire delivery from send()."""
import argparse, ipaddress, json, pathlib, struct

def packets(raw):
    position=0; endian='<'; interfaces=[]
    while position<len(raw):
        if position+12>len(raw):raise ValueError('Truncated pcapng block')
        if raw[position:position+4]==b'\x0a\x0d\x0d\x0a':
            magic=raw[position+8:position+12]
            endian='<' if magic==b'\x4d\x3c\x2b\x1a' else '>' if magic==b'\x1a\x2b\x3c\x4d' else None
            if endian is None:raise ValueError('Invalid pcapng section')
            interfaces=[]
        kind,size=struct.unpack_from(endian+'II',raw,position)
        if size<12 or size%4 or position+size>len(raw) or struct.unpack_from(endian+'I',raw,position+size-4)[0]!=size:raise ValueError('Invalid pcapng block length')
        body=raw[position+8:position+size-4]
        if kind==1:
            if len(body)<8:raise ValueError('Invalid pcapng interface')
            interfaces.append(struct.unpack_from(endian+'H',body)[0])
        elif kind==6:
            if len(body)<20:raise ValueError('Invalid pcapng packet')
            index,_,_,captured,_=struct.unpack_from(endian+'IIIII',body)
            if index>=len(interfaces) or captured>len(body)-20:raise ValueError('Invalid pcapng packet/interface')
            yield interfaces[index],body[20:20+captured]
        position+=size

def dns_query(frame,linktype,destination,identifier):
    if linktype==1:
        if len(frame)<14:return False
        ether=struct.unpack_from('!H',frame,12)[0];offset=14
        while ether in (0x8100,0x88a8):
            if len(frame)<offset+4:return False
            ether=struct.unpack_from('!H',frame,offset+2)[0];offset+=4
        if ether not in (0x0800,0x86dd):return False
        frame=frame[offset:]
    elif linktype not in (101,228,229):
        raise ValueError(f'Unsupported NIC link type {linktype}; refusing false-negative capture')
    if not frame:return False
    version=frame[0]>>4
    if version==4:
        if len(frame)<20 or frame[9]!=17:return False
        offset=(frame[0]&15)*4
        if offset<20 or ipaddress.ip_address(frame[16:20])!=destination:return False
        # Only the first fragment can contain the UDP/DNS header.
        if struct.unpack_from('!H',frame,6)[0]&0x1fff:return False
    elif version==6:
        if len(frame)<40 or frame[6]!=17:return False
        offset=40
        if ipaddress.ip_address(frame[24:40])!=destination:return False
    else:return False
    if len(frame)<offset+20:return False
    _,port,length,_=struct.unpack_from('!HHHH',frame,offset)
    payload=frame[offset+8:offset+length]
    return port==53 and len(payload)>=12 and struct.unpack_from('!H',payload)[0]==identifier and not payload[2]&0x80

def inspect(path,resolver,identifier):
    frames=list(packets(pathlib.Path(path).read_bytes()))
    count=sum(dns_query(frame,linktype,ipaddress.ip_address(resolver),identifier) for linktype,frame in frames)
    return {'packets':len(frames),'matching_dns_query_frames':count,'dns_id':identifier,'resolver':resolver}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('capture');p.add_argument('resolver');p.add_argument('identifier',type=int);a=p.parse_args()
    print(json.dumps(inspect(a.capture,a.resolver,a.identifier)))
