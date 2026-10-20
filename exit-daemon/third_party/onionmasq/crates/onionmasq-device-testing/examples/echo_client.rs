use anyhow::{anyhow, Result};
use futures::future::Either;
use futures::stream::StreamExt;
use log::LevelFilter;
use onionmasq_device_testing::AsyncDevice;
use simple_logger::SimpleLogger;
use smoltcp::iface::InterfaceBuilder;
use smoltcp::phy::Medium;
use smoltcp::socket::TcpSocket;
use smoltcp::storage::RingBuffer;
use smoltcp::time::Instant;
use smoltcp::wire::{IpAddress, IpCidr};
use std::os::unix::io::AsRawFd;
use tokio_tun::TunBuilder;

#[tokio::main]
async fn main() -> Result<()> {
    SimpleLogger::new()
        .with_level(LevelFilter::Off)
        .with_module_level("onionmasq_device_testing", LevelFilter::Trace)
        .init()
        .unwrap();
    let tun = TunBuilder::new()
        .name("test0")
        .tap(false)
        .packet_info(false)
        .up()
        .try_build()
        .map_err(|e| anyhow!("failed to create tun: {}", e))?;

    println!("tun created, name: {}, fd: {}", tun.name(), tun.as_raw_fd());

    let (dev, mut poll_dev) = AsyncDevice::<8192>::new(tun, Medium::Ip, 1500);

    let mut iface = InterfaceBuilder::new(dev, vec![])
        .ip_addrs(vec![IpCidr::new(IpAddress::v4(10, 0, 0, 1), 24)])
        .finalize();

    let socket = TcpSocket::new(
        RingBuffer::new(vec![0; 2000]),
        RingBuffer::new(vec![0; 2000]),
    );

    let handle = iface.add_socket(socket);
    {
        let (socket, ctxt) = iface.get_socket_and_context::<TcpSocket>(handle);

        socket
            .connect(
                ctxt,
                (IpAddress::v4(10, 0, 0, 2), 3000),
                (IpAddress::v4(10, 0, 0, 1), 65000),
            )
            .expect("socket connect");
    }
    loop {
        let now = Instant::now();
        eprintln!("iface poll:");
        eprintln!(
            "iface poll result {:?}, delay {:?}",
            iface.poll(now),
            iface.poll_delay(now)
        );
        let poll_delay = iface
            .poll_delay(now)
            .map(|x| Either::Left(tokio::time::sleep(std::time::Duration::from(x))))
            .unwrap_or_else(|| Either::Right(futures::future::pending::<()>()));
        tokio::select! {
            _ = poll_delay => {
                eprintln!("poll delay elapsed, repolling");
            }
            res = poll_dev.next() => {
                res.expect("asyncdevice error").expect("lol?");
                eprintln!("asyncdevice did something");
            }
        }
        let socket = iface.get_socket::<TcpSocket>(handle);
        eprintln!(
            "socket state: {:?}, can send: {:?}, can recv: {:?}",
            socket.state(),
            socket.can_send(),
            socket.can_recv()
        );
        if socket.can_recv() && socket.can_send() {
            let mut buf = vec![];
            socket
                .recv(|slice| {
                    buf.extend_from_slice(slice);
                    (slice.len(), ())
                })
                .expect("socket recv");
            let n = socket.send_slice(&buf).expect("socket send");
            eprintln!("echoed {}/{} bytes", n, buf.len());
        }
    }
}
