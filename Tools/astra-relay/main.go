package main

import (
    "context"
    "flag"
    "log"
    "net/http"
    "os/signal"
    "syscall"
    "time"
)

func main(){
    configPath:=flag.String("config","/etc/astra-relay/config.json","server configuration")
    flag.Parse()
    config,err:=loadConfig(*configPath);if err!=nil{log.Fatal("AstraRelay configuration unavailable: ",err)}
    server,err:=newRelay(config);if err!=nil{log.Fatal("AstraRelay initialization failed: ",err)}
    ctx,stop:=signal.NotifyContext(context.Background(),syscall.SIGTERM,syscall.SIGINT);defer stop()
    go func(){if err:=server.serveAdmin(ctx);err!=nil&&err!=http.ErrServerClosed{log.Print("AstraRelay local control failed");stop()}}()
    httpServer:=server.httpServer()
    go func(){<-ctx.Done();timeout,cancel:=context.WithTimeout(context.Background(),5*time.Second);defer cancel();_=httpServer.Shutdown(timeout);server.mu.Lock();streams:=make([]*wsStream,0,len(server.active));for stream:=range server.active{streams=append(streams,stream)};server.mu.Unlock();for _,stream:=range streams{stream.close(1001)}}()
    log.Print("AstraRelay started")
    if err=httpServer.ListenAndServeTLS(config.TLSCert,config.TLSKey);err!=nil&&err!=http.ErrServerClosed{log.Fatal("AstraRelay listener failed: ",err)}
}
