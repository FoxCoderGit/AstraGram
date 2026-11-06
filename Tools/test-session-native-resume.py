"""Execute production native resume/handshake methods with transport doubles on Windows."""
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
out=root/'Tools/tests/ghost89'
out.mkdir(parents=True,exist_ok=True)

def extract(path,signature):
    source=(root/path).read_text(encoding='utf-8')
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]

resume=extract('TMessagesProj/jni/tgnet/ConnectionsManager.cpp','void ConnectionsManager::setSessionDisabled(bool disabled)')
handshake=extract('TMessagesProj/jni/tgnet/Datacenter.cpp','void Datacenter::beginHandshake(HandshakeType handshakeType, bool reconnect)')
code=r'''
#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <vector>
#include <iostream>
#include <stdexcept>
enum HandshakeType{HandshakeTypeAll,HandshakeTypeCurrent,HandshakeTypePerm,HandshakeTypeTemp,HandshakeTypeMediaTemp};
constexpr bool PFS_ENABLED=true;
class Datacenter;
class Socket{public:void dropConnection(){throw std::runtime_error("unexpected leftover socket");}};
class ConnectionsManager{
public:
 static ConnectionsManager* instance;
 static ConnectionsManager& getInstance(int){return *instance;}
 std::atomic<bool> sessionDisabled{true};
 bool networkPaused=true;int lastPauseTime=11,lastMonotonicPauseTime=22,lastSystemPauseTime=33,restoredAuthFailures=2;
 std::map<int,Datacenter*> datacenters;std::vector<Socket*> activeConnections;
 std::vector<std::function<void()>> tasks;int packets=0,restarts=0,queueRuns=0;
 bool isSessionDisabled(){return sessionDisabled.load();}
 void scheduleTask(std::function<void()> f){tasks.push_back(f);}
 void drain(){auto q=std::move(tasks);tasks.clear();for(auto& f:q)f();}
 void processRequestQueue(int,int);
 void setSessionDisabled(bool disabled);
};
ConnectionsManager* ConnectionsManager::instance=nullptr;
class Handshake{
public:
 HandshakeType type;bool started=false;
 Handshake(Datacenter*,HandshakeType t,Datacenter*):type(t){}
 HandshakeType getType(){return type;}
 void beginHandshake(bool reconnect){auto& m=ConnectionsManager::getInstance(0);if(!m.isSessionDisabled()){started=true;m.packets++;if(reconnect)m.restarts++;}}
};
class Datacenter{
public:
 int instanceNum=0,key=123;void* authKeyPerm=&key;
 std::vector<std::unique_ptr<Handshake>> handshakes;
 bool hasMediaAddress(){return true;}
 bool isHandshakingAny(){return !handshakes.empty();}
 bool isHandshaking(HandshakeType t){for(auto& h:handshakes)if(h->type==t)return true;return false;}
 void suspendConnections(bool){ConnectionsManager::getInstance(0).activeConnections.clear();for(auto& h:handshakes)h->started=false;}
 void beginHandshake(HandshakeType,bool);
};
void ConnectionsManager::processRequestQueue(int,int){queueRuns++;for(auto& d:datacenters)if(!d.second->isHandshakingAny())d.second->beginHandshake(HandshakeTypeAll,true);}
METHODS
int main(){
 int checks=0;auto check=[&](bool b){if(!b)throw std::runtime_error("native resume regression");checks++;};
 ConnectionsManager m;ConnectionsManager::instance=&m;Datacenter d;m.datacenters[2]=&d;
 d.beginHandshake(HandshakeTypeAll,true);
 check(d.handshakes.empty()&&m.packets==0);
 // Reproduce a ghost.87/88 handshake whose first packet was discarded offline.
 d.handshakes.emplace_back(new Handshake(&d,HandshakeTypeTemp,&d));
 d.handshakes.emplace_back(new Handshake(&d,HandshakeTypeMediaTemp,&d));
 m.setSessionDisabled(false);check(!m.isSessionDisabled()&&m.packets==0);m.drain();
 check(m.packets==2&&m.restarts==2&&m.queueRuns==1);
 check(d.handshakes[0]->started&&d.handshakes[1]->started);
 check(!m.networkPaused&&m.lastPauseTime==0&&m.lastMonotonicPauseTime==0&&m.lastSystemPauseTime==0);
 check(d.authKeyPerm==&d.key&&d.key==123);
 Socket socket;m.activeConnections.push_back(&socket);m.setSessionDisabled(true);m.drain();
 check(m.isSessionDisabled()&&m.activeConnections.empty()&&!d.handshakes[0]->started&&d.key==123);
 m.setSessionDisabled(false);m.drain();check(m.packets==4&&d.handshakes[0]->started&&d.handshakes[1]->started);
 d.handshakes.clear();m.setSessionDisabled(false);m.drain();check(m.packets==6&&d.handshakes.size()==2);
 std::cout<<"PASS: "<<checks<<" production native resume/handshake checks, transport doubles only\n";
}
'''.replace('METHODS',resume+'\n'+handshake)
(out/'NativeResumeTest.cpp').write_text(code,encoding='utf-8')
runner=out/'run-native-resume.cmd'
runner.write_text(r'''@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\Tools\VsDevCmd.bat" -arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /std:c++17 /EHsc /O2 Tools\tests\ghost89\NativeResumeTest.cpp /Fe:Tools\tests\ghost89\native-resume.exe /Fo:Tools\tests\ghost89\native-resume.obj
if errorlevel 1 exit /b %errorlevel%
Tools\tests\ghost89\native-resume.exe
exit /b %errorlevel%
''',encoding='ascii')
subprocess.run(['cmd.exe','/c',str(runner)],cwd=root,check=True)
