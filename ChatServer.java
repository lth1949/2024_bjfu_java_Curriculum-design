import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.swing.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ChatServer extends JFrame {

    /**
     * 序列化版本号，用于确保反序列化时类的版本兼容性。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    // UI组件
    private JTextArea textArea;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JButton kickButton;
    private JButton startButton;
    private JButton stopButton;
    private JTextField portField;
    private JTextArea adminInputField;
    private JButton adminSendButton;

    // 网络组件
    private ServerSocket serverSocket;
    private Vector<ClientHandler> clients;
    private ConcurrentLinkedQueue<String> messageQueue; // 消息队列

    // 线程组件
    private AcceptThread acceptThread;
    private PatrolThread patrolThread;

    // 状态标志
    private volatile boolean isRunning;

    public ChatServer() {
        this.setTitle("全民聊天室服务器端");
        this.setSize(600, 600);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setLayout(new BorderLayout());

        // 初始化UI组件
        initTopPanel();         // 顶部面板（端口设置和按钮）
        initCenterPanel();      // 中央聊天区域
        initRightPanel();       // 右侧用户列表
        initAdminInputPanel();  // 管理员输入面板

        // 初始化其他组件
        initComponents();

        // 设置事件监听器
        initEventListeners();

        // 初始化键盘绑定
        initKeyBindings();

        this.setVisible(true);
    }

    /**
     * 初始化顶部面板（端口设置和按钮）
     */
    private void initTopPanel() {
        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("端口:"));
        this.portField = new JTextField("12345", 10);
        topPanel.add(this.portField);

        this.startButton = new JButton("启动服务");
        this.stopButton = new JButton("关停服务");
        this.stopButton.setEnabled(false);

        topPanel.add(this.startButton);
        topPanel.add(this.stopButton);
        this.add(topPanel, BorderLayout.NORTH);
    }

    /**
     * 初始化中央聊天区域
     */
    private void initCenterPanel() {
        this.textArea = new JTextArea();
        this.textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(this.textArea);
        this.add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * 初始化右侧用户列表面板
     */
    private void initRightPanel() {
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BorderLayout());
        rightPanel.add(new JLabel("聊客列表："), BorderLayout.NORTH);

        this.userListModel = new DefaultListModel<>();
        // 初始化时添加"暂无聊客"提示
        this.userListModel.addElement("暂无聊客");
        this.userList = new JList<>(this.userListModel);

        JScrollPane userScroll = new JScrollPane(this.userList);
        rightPanel.add(userScroll, BorderLayout.CENTER);

        this.kickButton = new JButton("踢出一名聊客");
        this.kickButton.setEnabled(false); // 初始状态设为不可用
        rightPanel.add(this.kickButton, BorderLayout.SOUTH);

        this.add(rightPanel, BorderLayout.EAST);
    }

    /**
     * 初始化管理员输入面板
     */
    private void initAdminInputPanel() {
        JPanel adminInputPanel = new JPanel(new BorderLayout());

        // 创建多行文本输入区域（3行高度）
        adminInputField = new JTextArea(3, 20);
        adminInputField.setLineWrap(true);  // 自动换行
        adminInputField.setWrapStyleWord(true); // 按单词换行

        // 添加滚动条
        JScrollPane scrollPane = new JScrollPane(adminInputField);

        // 创建发送按钮
        adminSendButton = new JButton("发送");
        adminSendButton.setEnabled(false); // 初始状态设为不可用

        adminInputPanel.add(scrollPane, BorderLayout.CENTER);
        adminInputPanel.add(adminSendButton, BorderLayout.EAST);

        this.add(adminInputPanel, BorderLayout.SOUTH);
    }

    /**
     * 初始化其他组件
     */
    private void initComponents() {
        this.clients = new Vector<>();
        this.messageQueue = new ConcurrentLinkedQueue<>();
        this.adminSendButton.setEnabled(false);
    }

    /**
     * 初始化事件监听器
     */
    private void initEventListeners() {
        // 启动按钮监听器
        this.startButton.addActionListener(e -> startServer());

        // 停止按钮监听器
        this.stopButton.addActionListener(e -> stopServer());

        // 踢出按钮监听器
        this.kickButton.addActionListener(e -> {
            String selectedUser = userList.getSelectedValue();
            if (selectedUser == null) {
                // 未选中用户时提示
                JOptionPane.showMessageDialog(ChatServer.this,
                        "未选择聊客", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            kickUser(selectedUser);
        });

        // 管理员发送按钮监听器
        adminSendButton.addActionListener(e -> sendAdminMessage());
    }

    /**
     * 初始化键盘绑定
     */
    private void initKeyBindings() {
        InputMap inputMap = adminInputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = adminInputField.getActionMap();

        // 设置Enter键发送消息（无修饰键）
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, false), "sendAction");
        actionMap.put("sendAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendAdminMessage();
                // 阻止默认的换行行为
                adminInputField.setCaretPosition(adminInputField.getCaretPosition());
            }
        });

        // 设置Shift+Enter为插入换行符
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, false), "insertNewline");
        actionMap.put("insertNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int caretPos = adminInputField.getCaretPosition();
                adminInputField.insert("\n", caretPos);
            }
        });
    }

    /**
     * 创建管理员消息输入面板
     * @return 管理员消息输入面板
     */
    private JPanel createAdminInputPanel() {
        JPanel adminInputPanel = new JPanel(new BorderLayout());

        // 创建多行文本输入区域（3行高度）
        adminInputField = new JTextArea(3, 20);
        adminInputField.setLineWrap(true);  // 自动换行
        adminInputField.setWrapStyleWord(true); // 按单词换行

        // 添加滚动条
        JScrollPane scrollPane = new JScrollPane(adminInputField);

        // 创建发送按钮
        adminSendButton = new JButton("发送");
        adminSendButton.setEnabled(false); // 初始状态设为不可用

        adminInputPanel.add(scrollPane, BorderLayout.CENTER);
        adminInputPanel.add(adminSendButton, BorderLayout.EAST);

        return adminInputPanel;
    }

    /**
     * 发送管理员消息
     */

    private void sendAdminMessage() {
        if (!isRunning) return;
        String message = adminInputField.getText().trim();
        if (!message.isEmpty()) {
            // 按行拆分输入内容
            String[] lines = message.split("\n");

            // 为每行创建独立的消息
            for (String line : lines) {
                if (!line.trim().isEmpty()) {  // 忽略空行
                    broadcast("管理员：" + line);
                }
            }

            adminInputField.setText("");
            adminInputField.requestFocusInWindow();
        }
    }

    /**
     * 启动聊天服务器
     */
    private void startServer() {
        int port;
        try {
            // 解析端口号
            port = Integer.parseInt(this.portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "端口号非法！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // 创建服务器套接字
            this.serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法在端口 " + port + " 启动服务器！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 调整按钮状态
        this.isRunning = true;
        this.startButton.setEnabled(false);
        this.portField.setEditable(false);
        this.stopButton.setEnabled(true);
        this.adminSendButton.setEnabled(true);

        // 启动接受客户端连接线程
        this.acceptThread = new AcceptThread();
        this.acceptThread.start();

        // 启动巡逻线程
        this.patrolThread = new PatrolThread();
        this.patrolThread.start();

        // 更新用户列表
        SwingUtilities.invokeLater(() -> {
            this.userListModel.clear();
            this.userListModel.addElement("暂无聊客");
        });

        // 输出启动信息
        System.out.println("服务器启动，端口：" + port);
    }

    /**
     * 停止聊天服务器
     */
    private void stopServer() {
        // 广播服务器关闭消息
        String shutdownMsg = "管理员：【服务器关闭，大家都散了吧。】";
        this.broadcast(shutdownMsg);

        // 设置服务器状态为停止
        this.isRunning = false;

        try {
            // 关闭服务器套接字
            if (this.serverSocket != null) {
                this.serverSocket.close();
            }
        } catch (IOException e) {
            // 忽略关闭异常
        }

        // 关闭所有客户端连接
        synchronized(this.clients) {
            for(ClientHandler client : this.clients) {
                client.closeConnection();
            }
            // 清空客户端列表
            this.clients.clear();
        }

        // 清空用户列表
        this.userListModel.clear();
        System.out.println("服务器已停止");

        // 调整按钮状态
        this.startButton.setEnabled(true);
        this.portField.setEditable(true);
        this.stopButton.setEnabled(false);
        this.kickButton.setEnabled(false);
        this.adminSendButton.setEnabled(false);
    }

    /**
     * 踢出指定用户
     * @param username 要踢出的用户名
     */
    private void kickUser(String username) {
        ClientHandler target = null;

        // 在客户端列表中查找目标用户
        synchronized(this.clients) {
            for(ClientHandler client : this.clients) {
                if (client.getUserName().equals(username)) {
                    target = client;
                    break;
                }
            }
        }

        if (target != null) {
            // 广播踢出消息
            String msg =  username + "：【因违规被踢出群聊室】";
            this.broadcast(msg);

            // 向被踢用户发送通知
            target.sendMessage("管理员: 你已被踢出群聊，下次注意！！！");

            // 关闭连接并移除用户
            target.closeConnection();
            this.removeClient(target);

            // 记录日志
            System.out.println("踢出用户：" + username);
        }
    }

    /**
     * 从服务器中移除客户端
     * @param client 要移除的客户端处理器
     */
    private void removeClient(ClientHandler client) {
        // 从客户端列表中移除
        synchronized(this.clients) {
            this.clients.remove(client);
        }

        // 更新用户列表UI
        SwingUtilities.invokeLater(() -> {
            this.userListModel.removeElement(client.getUserName());
            // 检查在线用户列表是否为空，为空则显示提示信息
            if (this.userListModel.isEmpty()) {
                this.userListModel.addElement("暂无聊客");
                // 禁用踢出按钮
                kickButton.setEnabled(false);
            }
        });
    }

    /**
     * 广播消息给所有客户端
     * @param message 要广播的消息内容
     */
    private void broadcast(String message) {
        // 添加时间前缀
        LocalTime now = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        String timePrefix = "【" + now.format(formatter) + "】";
        String formattedMessage = timePrefix + message;

        // 记录广播消息
        System.out.println("Broadcast: " + formattedMessage);

        // 在聊天区域显示消息
        SwingUtilities.invokeLater(() -> this.textArea.append(formattedMessage + "\n"));

        // 向所有客户端发送消息
        synchronized(this.clients) {
            for(ClientHandler client : this.clients) {
                client.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * 添加日志消息
     * @param message 要添加的日志消息
     */
    private void appendLog(String message) {
        // 在聊天区域显示日志
        SwingUtilities.invokeLater(() -> this.textArea.append(message + "\n"));
        // 在控制台输出日志
        System.out.println(message);
    }

    /**
     * 主程序入口
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 在Swing事件调度线程中创建服务器窗口
        SwingUtilities.invokeLater(() -> new ChatServer());
    }

    /**
     * 接受客户端连接线程类
     */
    class AcceptThread extends Thread {
        /**
         * 构造函数，初始化用户列表
         */
        AcceptThread() {
            // 如果有"暂无聊客"提示，移除它
            if (ChatServer.this.userListModel.size() == 1 &&
                    ChatServer.this.userListModel.getElementAt(0).equals("暂无聊客")) {
                ChatServer.this.userListModel.removeElementAt(0);
            }
        }

        /**
         * 线程主方法，接受客户端连接
         */
        public void run() {
            while(true) {
                if (ChatServer.this.isRunning) {
                    try {
                        // 接受客户端连接
                        Socket socket = ChatServer.this.serverSocket.accept();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                        // 读取客户端发送的用户名
                        String name = reader.readLine();
                        boolean nameExists = false;

                        // 检查用户名是否已存在
                        synchronized(ChatServer.this.clients) {
                            for(ClientHandler client : ChatServer.this.clients) {
                                if (client.getUserName().equals(name)) {
                                    nameExists = true;
                                    break;
                                }
                            }
                        }

                        // 验证用户名有效性
                        if (name != null && !name.trim().equals("") && !name.equals("管理员") && !nameExists) {
                            // 用户名有效，发送确认消息
                            writer.println("OK");

                            // 创建客户端处理器
                            ClientHandler clientHandler = ChatServer.this.new ClientHandler(socket, name);

                            // 添加到客户端列表
                            synchronized(ChatServer.this.clients) {
                                ChatServer.this.clients.add(clientHandler);
                            }

                            // 更新用户列表UI
                            SwingUtilities.invokeLater(() -> {
                                // 有用户加入时，若列表只有提示信息则先移除
                                if (ChatServer.this.userListModel.size() == 1 &&
                                        ChatServer.this.userListModel.getElementAt(0).equals("暂无聊客")) {
                                    ChatServer.this.userListModel.removeElementAt(0);
                                }
                                // 启用踢出按钮
                                kickButton.setEnabled(true);
                                // 添加新用户
                                ChatServer.this.userListModel.addElement(name);
                            });

                            // 启动客户端线程
                            clientHandler.start();

                            // 记录连接信息
                            System.out.println("用户 " + name + " 已连接");

                            // 添加用户进入消息到队列
                            ChatServer.this.messageQueue.offer(name + "：【进入了聊天室】");
                            continue;
                        }

                        // 用户名无效，发送拒绝消息并关闭连接
                        writer.println("INVALID");
                        socket.close();
                        continue;
                    } catch (IOException e) {
                        if (ChatServer.this.isRunning) {
                            // 记录连接错误
                            ChatServer.this.appendLog("接收连接时发生错误: " + e.getMessage());
                        }
                    }
                }
                // 服务器已停止，退出线程
                System.out.println("接受线程正常退出");
                return;
            }
        }
    }
    /**
     * 巡逻线程类，负责处理消息队列和检查客户端状态
     */
    class PatrolThread extends Thread {
        PatrolThread() {
            // 巡逻线程构造函数，负责监控客户端连接和消息队列
        }

        public void run() {
            while (true) {
                if (ChatServer.this.isRunning) {
                    // 处理消息队列中的所有待广播消息
                    while (!ChatServer.this.messageQueue.isEmpty()) {
                        String msg = ChatServer.this.messageQueue.poll();
                        if (msg != null) {
                            try {
                                // 安全广播消息
                                ChatServer.this.broadcast(msg);
                            } catch (Exception e) {
                                // 记录广播失败信息
                                System.out.println("广播消息[" + msg + "]时出错: " + e.getMessage());
                            }
                        }
                    }

                    // 检查所有客户端连接状态
                    synchronized (ChatServer.this.clients) {
                        Iterator<ClientHandler> iter = ChatServer.this.clients.iterator();

                        while (iter.hasNext()) {
                            ClientHandler client = iter.next();
                            try {
                                // 检查客户端线程是否存活
                                if (!client.isAlive()) {
                                    String name = client.getUserName();
                                    iter.remove(); // 从客户端列表移除

                                    // 在Swing线程中安全更新UI
                                    SwingUtilities.invokeLater(() -> {
                                        try {
                                            ChatServer.this.userListModel.removeElement(name);
                                            // 如果用户列表为空，显示提示信息
                                            if (ChatServer.this.userListModel.isEmpty()) {
                                                ChatServer.this.userListModel.addElement("暂无聊客");
                                                // 禁用踢出按钮
                                                kickButton.setEnabled(false);
                                            }
                                        } catch (Exception e) {
                                            // 处理UI更新异常
                                            System.out.println("更新用户列表(" + name + ")时出错: " + e.getMessage());
                                        }
                                    });

                                    // 广播用户离开消息
                                    String leaveMsg = name + "：【离开了聊天室】";
                                    try {
                                        ChatServer.this.broadcast(leaveMsg);
                                    } catch (Exception e) {
                                        // 处理广播异常
                                        System.out.println("广播离开消息(" + leaveMsg + ")时出错: " + e.getMessage());
                                    }

                                    // 在终端记录用户断开
                                    System.out.println("用户 " + name + " 已断开连接");
                                }
                            } catch (Exception e) {
                                // 处理客户端状态检查异常
                                System.out.println("检查客户端[" + client.getUserName() + "]状态时出错: " + e.getMessage());
                            }
                        }
                    }

                    try {
                        // 短暂休眠，减少CPU占用
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        // 处理线程中断
                        System.out.println("巡逻线程被意外中断: " + e.getMessage());
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        break; // 退出循环
                    }
                } else {
                    // 服务器已停止运行，退出线程
                    System.out.println("巡逻线程正常退出");
                    return;
                }
            }
        }
    }

    /**
     * 客户端处理器类，负责处理单个客户端连接
     */
    class ClientHandler extends Thread {
        private Socket socket;
        private String userName;
        private BufferedReader reader;
        private PrintWriter writer;
        private volatile boolean connected;

        public ClientHandler(Socket socket, String name) {
            this.socket = socket;
            this.userName = name;
            this.connected = true;

            try {
                // 初始化输入输出流
                this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                this.writer = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e) {
                // 初始化连接时出错
                System.out.println("初始化用户 " + name + " 的连接时出错: " + e.getMessage());
            }
        }

        public String getUserName() {
            return this.userName;
        }

        public void sendMessage(String message) {
            if (this.connected) {
                this.writer.println(message);
            }
        }

        public void closeConnection() {
            this.connected = false;
            try {
                // 确保socket未被关闭
                if (!this.socket.isClosed()) {
                    this.socket.close();
                }
            } catch (IOException e) {
                // 关闭连接时出错
                System.out.println("关闭用户 " + this.userName + " 的连接时出错: " + e.getMessage());
            }
        }

        public void run() {
            try {
                String line;
                try {
                    // 持续读取客户端消息
                    while(this.connected && (line = this.reader.readLine()) != null) {
                        String message = this.userName + "：" + line;
                        ChatServer.this.messageQueue.offer(message);
                    }
                } catch (IOException e) {
                    // 读取消息时发生异常（通常是客户端异常断开）
                    System.out.println("用户 " + this.userName + " 读取消息时异常断开: " + e.getMessage());
                }
            } finally {
                // 确保连接被关闭
                this.connected = false;
                try {
                    // 再次尝试关闭socket（双重保障）
                    if (!this.socket.isClosed()) {
                        this.socket.close();
                    }
                } catch (IOException e) {
                    // 最终关闭连接时出错
                    System.out.println("最终关闭用户 " + this.userName + " 的连接时出错: " + e.getMessage());
                }
            }
        }
    }
}