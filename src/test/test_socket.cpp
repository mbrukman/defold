#include <stdint.h>
#include <gtest/gtest.h>
#include "../dlib/socket.h"
#include "../dlib/thread.h"
#include "../dlib/time.h"

TEST(Socket, Basic1)
{
    dmSocket::Socket socket;
    dmSocket::Result r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket);
    EXPECT_EQ(r, dmSocket::RESULT_OK);

    dmSocket::Address local_address = dmSocket::AddressFromIPString("127.0.0.1");
    r = dmSocket::Connect(socket, local_address, 1);

    EXPECT_EQ(dmSocket::RESULT_CONNREFUSED, r);

    r = dmSocket::Delete(socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
}

TEST(Socket, GetHostByName1)
{
    dmSocket::Address local_address = dmSocket::AddressFromIPString("127.0.0.1");

    dmSocket::Address a;
    dmSocket::Result r = dmSocket::GetHostByName("localhost", &a);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
    EXPECT_EQ(local_address, a);
}

TEST(Socket, GetHostByName2)
{
    dmSocket::Address a;
    dmSocket::Result r = dmSocket::GetHostByName("host.nonexistingdomain", &a);
#ifdef _WIN32
    // NOTE: No idea why.
    ASSERT_EQ(dmSocket::RESULT_NO_DATA, r);
#else
    ASSERT_EQ(dmSocket::RESULT_HOST_NOT_FOUND, r);
#endif
}

TEST(Socket, ServerSocket1)
{
    dmSocket::Socket socket;
    dmSocket::Result r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    const int port = 9000;

    r = dmSocket::Bind(socket, dmSocket::AddressFromIPString("0.0.0.0"), port);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Listen(socket, 1000);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
    
    r = dmSocket::Delete(socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
}

TEST(Socket, ServerSocket2)
{
    dmSocket::Socket socket1, socket2;
    dmSocket::Result r;
    r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket1);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
    r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket2);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    const int port = 9000;

    r = dmSocket::Bind(socket1, dmSocket::AddressFromIPString("0.0.0.0"), port);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Bind(socket2, dmSocket::AddressFromIPString("0.0.0.0"), port);
    ASSERT_EQ(dmSocket::RESULT_ADDRINUSE, r);

    r = dmSocket::Delete(socket1);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Delete(socket2);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
}

TEST(Socket, ServerSocket3)
{
    dmSocket::Socket socket;
    dmSocket::Result r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::SetBlocking(socket, false);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    const int port = 9000;

    r = dmSocket::Bind(socket, dmSocket::AddressFromIPString("0.0.0.0"), port);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Listen(socket, 1000);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    dmSocket::Address address;
    dmSocket::Socket client_socket;
    r = dmSocket::Accept(socket, &address, &client_socket);
    ASSERT_EQ(dmSocket::RESULT_WOULDBLOCK, r);
    
    r = dmSocket::Delete(socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
}

bool g_ServerThread1Running = false;
int  g_ServerThread1Port = 9002;
static void ServerThread1(void* arg)
{
    dmSocket::Socket socket;
    dmSocket::Result r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::SetReuseAddress(socket, true);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    const int port = g_ServerThread1Port;

    r = dmSocket::Bind(socket, dmSocket::AddressFromIPString("0.0.0.0"), port);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Listen(socket, 1000);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    dmSocket::Address address;
    dmSocket::Socket client_socket;
    g_ServerThread1Running = true;
    r = dmSocket::Accept(socket, &address, &client_socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    int value = 1234;
    int sent_bytes;
    r = dmSocket::Send(client_socket, &value, sizeof(value), &sent_bytes);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
    ASSERT_EQ((int) sizeof(value), sent_bytes);

    r = dmSocket::Delete(client_socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    r = dmSocket::Delete(socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
}

TEST(Socket, ClientServer1)
{
    dmThread::Thread thread = dmThread::New(&ServerThread1, 0x80000, 0);

    dmSocket::Socket socket;
    dmSocket::Result r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &socket);
    ASSERT_EQ(r, dmSocket::RESULT_OK);

    dmSocket::Address local_address = dmSocket::AddressFromIPString("127.0.0.1");

    for (int i = 0; i < 100; ++i)
    {
        if (g_ServerThread1Running)
            break;

        dmSleep(10);
    }
    dmSleep(50); // Make sure that we are in "accept"

    ASSERT_EQ(true, g_ServerThread1Running);

    r = dmSocket::Connect(socket, local_address, g_ServerThread1Port);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    int value;
    int received_bytes;
    r = dmSocket::Receive(socket, &value, sizeof(value), &received_bytes);
    ASSERT_EQ(dmSocket::RESULT_OK, r);
    ASSERT_EQ((int) sizeof(received_bytes), received_bytes);
    ASSERT_EQ(1234, value);

    r = dmSocket::Delete(socket);
    ASSERT_EQ(dmSocket::RESULT_OK, r);

    dmThread::Join(thread);
}


int main(int argc, char **argv)
{
    dmSocket::Setup();
    testing::InitGoogleTest(&argc, argv);
    int ret = RUN_ALL_TESTS();
    dmSocket::Shutdown();
    return ret;
}

