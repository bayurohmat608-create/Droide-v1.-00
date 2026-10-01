typedef unsigned long u64;
typedef long s64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

#define SYS_openat 56
#define SYS_close 57
#define SYS_read 63
#define SYS_write 64
#define SYS_exit 93
#define SYS_socket 198
#define SYS_connect 203
#define SYS_sendto 206
#define AT_FDCWD (-100)
#define AF_UNIX 1
#define SOCK_STREAM 1
#define EINTR 4
#define MSG_NOSIGNAL 0x4000
#define EXIT_USAGE 2
#define EXIT_UNAVAILABLE 69
#define EXIT_INTERNAL 70
#define MAX_ENDPOINT 512
#define MAX_SOCKET_NAME 100
#define MAX_ARGS 128
#define MAX_ARG_BYTES 4096
#define MAX_REQUEST_BYTES 16384
#define MAX_STREAM_BYTES 65536

static const char endpoint_path[] = "/opt/droide/cli/endpoint-v1";
static const u8 request_magic[4] = {'D','R','Q','1'};
static const u8 response_magic[4] = {'D','R','S','1'};

static inline s64 syscall1(s64 n, s64 a0) { register s64 x8 __asm__("x8")=n; register s64 x0 __asm__("x0")=a0; __asm__ volatile("svc #0":"+r"(x0):"r"(x8):"memory"); return x0; }
static inline s64 syscall3(s64 n, s64 a0, s64 a1, s64 a2) { register s64 x8 __asm__("x8")=n; register s64 x0 __asm__("x0")=a0; register s64 x1 __asm__("x1")=a1; register s64 x2 __asm__("x2")=a2; __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x8):"memory"); return x0; }
static inline s64 syscall6(s64 n, s64 a0, s64 a1, s64 a2, s64 a3, s64 a4, s64 a5) { register s64 x8 __asm__("x8")=n; register s64 x0 __asm__("x0")=a0; register s64 x1 __asm__("x1")=a1; register s64 x2 __asm__("x2")=a2; register s64 x3 __asm__("x3")=a3; register s64 x4 __asm__("x4")=a4; register s64 x5 __asm__("x5")=a5; __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8):"memory"); return x0; }

static u64 slen(const char *s) { u64 n=0; while (s[n]) n++; return n; }
static void zero(void *p, u64 n) { u8 *b=(u8*)p; while(n--) *b++=0; }
static int same4(const u8 *a, const u8 *b) { return a[0]==b[0] && a[1]==b[1] && a[2]==b[2] && a[3]==b[3]; }
static void put32be(u8 *p, u32 v) { p[0]=(u8)(v>>24); p[1]=(u8)(v>>16); p[2]=(u8)(v>>8); p[3]=(u8)v; }
static u32 get32be(const u8 *p) { return ((u32)p[0]<<24)|((u32)p[1]<<16)|((u32)p[2]<<8)|p[3]; }

static int write_all(int fd, const void *buf, u64 len) {
    const u8 *p=(const u8*)buf;
    while (len) {
        s64 n=syscall3(SYS_write,fd,(s64)p,len);
        if (n==-EINTR) continue;
        if (n<=0) return -1;
        p+=n; len-=(u64)n;
    }
    return 0;
}
static int send_all(int fd, const void *buf, u64 len) {
    const u8 *p=(const u8*)buf;
    while (len) {
        s64 n=syscall6(SYS_sendto,fd,(s64)p,len,MSG_NOSIGNAL,0,0);
        if (n==-EINTR) continue;
        if (n<=0) return -1;
        p+=n; len-=(u64)n;
    }
    return 0;
}

static int read_all(int fd, void *buf, u64 len) {
    u8 *p=(u8*)buf;
    while (len) {
        s64 n=syscall3(SYS_read,fd,(s64)p,len);
        if (n==-EINTR) continue;
        if (n<=0) return -1;
        p+=n; len-=(u64)n;
    }
    return 0;
}
static void message(int fd, const char *s) { write_all(fd,s,slen(s)); }

static int read_endpoint(char *name, u64 cap) {
    s64 fd=syscall3(SYS_openat,AT_FDCWD,(s64)endpoint_path,0);
    if (fd<0) return -1;
    char buf[MAX_ENDPOINT]; u64 used=0;
    for (;;) {
        if (used>=MAX_ENDPOINT-1) { syscall1(SYS_close,fd); return -1; }
        s64 n=syscall3(SYS_read,fd,(s64)(buf+used),(MAX_ENDPOINT-1)-used);
        if (n==-EINTR) continue;
        if (n<0) { syscall1(SYS_close,fd); return -1; }
        if (n==0) break;
        used+=(u64)n;
    }
    syscall1(SYS_close,fd);
    if (used==0) return -1; buf[used]=0;
    const char magic[]="DROIDE_CLI_ENDPOINT_V1\n"; u64 m=slen(magic), i=0;
    while (i<m) { if (used<=i || buf[i]!=magic[i]) return -1; i++; }
    u64 out=0; while (i<used && buf[i]!='\n') { if (out+1>=cap) return -1; char c=buf[i++]; if (!(c=='.'||c=='-'||c=='_'||(c>='0'&&c<='9')||(c>='A'&&c<='Z')||(c>='a'&&c<='z'))) return -1; name[out++]=c; }
    if (out==0 || i>=used || buf[i]!='\n') return -1; name[out]=0;
    return (int)out;
}

struct sockaddr_un_local { u16 family; char path[108]; };

static int connect_cli(void) {
    char name[MAX_SOCKET_NAME+1]; int n=read_endpoint(name,sizeof(name)); if (n<=0) return -1;
    s64 fd=syscall3(SYS_socket,AF_UNIX,SOCK_STREAM,0); if (fd<0) return -1;
    struct sockaddr_un_local addr; zero(&addr,sizeof(addr)); addr.family=AF_UNIX; addr.path[0]=0;
    for (int i=0;i<n;i++) addr.path[i+1]=name[i];
    if (syscall3(SYS_connect,fd,(s64)&addr,(s64)(2+1+n))<0) { syscall1(SYS_close,fd); return -1; }
    return (int)fd;
}

static int validate_request(int argc, char **argv) {
    int count=argc-1; if (count<1 || count>MAX_ARGS) return -1;
    u64 total=8;
    for (int i=1;i<argc;i++) {
        u64 n=slen(argv[i]); if (n<1 || n>MAX_ARG_BYTES) return -1;
        total += 4+n; if (total>MAX_REQUEST_BYTES) return -1;
    }
    return 0;
}

static int send_request(int fd, int argc, char **argv) {
    int count=argc-1;
    if (send_all(fd,request_magic,4)<0) return -1;
    u8 word[4]; put32be(word,(u32)count); if (send_all(fd,word,4)<0) return -1;
    for (int i=1;i<argc;i++) {
        u64 n=slen(argv[i]);
        put32be(word,(u32)n); if (send_all(fd,word,4)<0 || send_all(fd,argv[i],n)<0) return -1;
    }
    return 0;
}

static int relay_stream(int fd, int outfd, u32 len) {
    if (len>MAX_STREAM_BYTES) return -1;
    u8 buf[1024]; u32 remaining=len;
    while (remaining) { u32 chunk=remaining>sizeof(buf)?sizeof(buf):remaining; if (read_all(fd,buf,chunk)<0) return -1; if (write_all(outfd,buf,chunk)<0) return -1; remaining-=chunk; }
    return 0;
}

static int receive_response(int fd) {
    u8 header[16]; if (read_all(fd,header,sizeof(header))<0 || !same4(header,response_magic)) return EXIT_INTERNAL;
    u32 code=get32be(header+4), outlen=get32be(header+8), errlen=get32be(header+12);
    if (code>255 || outlen>MAX_STREAM_BYTES || errlen>MAX_STREAM_BYTES) return EXIT_INTERNAL;
    if (relay_stream(fd,1,outlen)<0 || relay_stream(fd,2,errlen)<0) return EXIT_INTERNAL;
    return (int)code;
}

__attribute__((used,noinline)) int droide_main(u64 *stack) {
    int argc=(int)stack[0]; char **argv=(char**)&stack[1];
    if (argc<2) { message(2,"Usage: droide pkg|plugin|runtime ...\n"); return EXIT_USAGE; }
    if (validate_request(argc,argv)<0) { message(2,"droide: request is invalid or too large\n"); return EXIT_USAGE; }
    int fd=connect_cli(); if (fd<0) { message(2,"droide: CLI endpoint is unavailable for this workspace\n"); return EXIT_UNAVAILABLE; }
    if (send_request(fd,argc,argv)<0) { syscall1(SYS_close,fd); message(2,"droide: request transmission failed\n"); return EXIT_INTERNAL; }
    int code=receive_response(fd); syscall1(SYS_close,fd); return code;
}

__attribute__((naked,noreturn)) void _start(void) {
    __asm__ volatile("mov x0, sp\nbl droide_main\nmov x8, #93\nsvc #0\n");
}
