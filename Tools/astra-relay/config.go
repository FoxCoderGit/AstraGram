package main

import (
    "encoding/json"
    "errors"
    "net"
    "os"
    "path/filepath"
    "sync"
)

const appID = "org.ghostgram.messenger"
const protocol = "astrarelay.v1"
const daySeconds int64 = 86400

type Endpoint struct {
    Host string `json:"host"`
    IPs []string `json:"ips"`
    Port int `json:"port"`
    Path string `json:"path"`
    Pins []string `json:"pins"`
}

type Config struct {
    Listen string `json:"listen"`
    TLSCert string `json:"tls_cert"`
    TLSKey string `json:"tls_key"`
    SigningKey string `json:"signing_key"`
    TokenKey string `json:"token_key"`
    PolicyFile string `json:"policy_file"`
    AdminSocket string `json:"admin_socket"`
    Endpoint Endpoint `json:"endpoint"`
    CIDRs []string `json:"cidrs"`
    Ports []int `json:"ports"`
    MaxDeviceConnections int `json:"max_device_connections"`
    MaxIPConnections int `json:"max_ip_connections"`
}

type Policy struct {
    Revision uint64 `json:"revision"`
    Enabled bool `json:"enabled"`
    Denied map[string]bool `json:"denied_devices"`
}

var telegramCIDRs = []string{
    "91.108.56.0/22", "91.108.4.0/22", "91.108.8.0/22", "91.108.16.0/22",
    "91.108.12.0/22", "149.154.160.0/20", "91.105.192.0/23", "91.108.20.0/22",
    "185.76.151.0/24", "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48",
    "2001:b28:f23c::/48", "2a0a:f280::/32",
}

func loadConfig(path string) (Config,error) {
    var c Config
    data,err:=os.ReadFile(path); if err!=nil{return c,err}
    if err=json.Unmarshal(data,&c);err!=nil{return c,err}
    if c.Listen=="" || c.TLSCert=="" || c.TLSKey=="" || c.SigningKey=="" || c.TokenKey=="" || c.PolicyFile=="" || c.AdminSocket=="" {return c,errors.New("missing server configuration")}
    if c.Endpoint.Host=="" || len(c.Endpoint.IPs)==0 || c.Endpoint.Port<1 || c.Endpoint.Port>65535 || c.Endpoint.Path!="/v1/tunnel" {return c,errors.New("invalid public endpoint")}
    for _,ip:=range c.Endpoint.IPs {if net.ParseIP(ip)==nil{return c,errors.New("invalid relay IP")}}
    if len(c.CIDRs)==0 {c.CIDRs=telegramCIDRs}
    if len(c.Ports)==0 {c.Ports=[]int{80,443,5222}}
    if c.MaxDeviceConnections==0 {c.MaxDeviceConnections=48}
    if c.MaxIPConnections==0 {c.MaxIPConnections=256}
    if c.MaxDeviceConnections<1 || c.MaxDeviceConnections>128 || c.MaxIPConnections<1 || c.MaxIPConnections>4096{return c,errors.New("invalid connection limits")}
    return c,nil
}

func atomicJSON(path string,value any) error {
    data,err:=json.MarshalIndent(value,"","  ");if err!=nil{return err}
    tmp,err:=os.CreateTemp(filepath.Dir(path),".astra-policy-*");if err!=nil{return err}
    name:=tmp.Name();defer os.Remove(name)
    if err=tmp.Chmod(0600);err==nil{_,err=tmp.Write(data)}
    if err==nil{err=tmp.Sync()};closeErr:=tmp.Close();if err==nil{err=closeErr}
    if err!=nil{return err};return os.Rename(name,path)
}

type destinationFilter struct {nets []*net.IPNet;ports map[int]bool}
func newFilter(cidrs []string,ports []int)(destinationFilter,error){
    f:=destinationFilter{ports:map[int]bool{}}
    for _,value:=range cidrs {_,network,err:=net.ParseCIDR(value);if err!=nil{return f,err};ones,bits:=network.Mask.Size();if ones<16 || bits==128&&ones<32{return f,errors.New("destination range too broad")};f.nets=append(f.nets,network)}
    for _,port:=range ports {if port<1||port>65535{return f,errors.New("invalid destination port")};f.ports[port]=true}
    return f,nil
}
func(f destinationFilter) allows(address string,port int)bool{
    ip:=net.ParseIP(address)
    if ip==nil||!f.ports[port]||ip.IsLoopback()||ip.IsPrivate()||ip.IsUnspecified()||ip.IsMulticast()||ip.IsLinkLocalUnicast(){return false}
    for _,network:=range f.nets{if network.Contains(ip){return true}}
    return false
}

type budgetEntry struct{window int64;count int}
type rateBudget struct{mu sync.Mutex;entries map[string]budgetEntry}
func(b *rateBudget)take(key string,now int64,limit int)bool{
    b.mu.Lock();defer b.mu.Unlock()
    if b.entries==nil{b.entries=map[string]budgetEntry{}}
    window:=now/60;entry:=b.entries[key]
    if entry.window!=window{entry=budgetEntry{window:window}}
    if entry.count>=limit{return false}
    if len(b.entries)>=16384 {for k,v:=range b.entries{if v.window<window-1{delete(b.entries,k)}};if len(b.entries)>=16384{return false}}
    entry.count++;b.entries[key]=entry;return true
}
