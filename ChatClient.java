import java.io.*;
import java.net.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * 客户端主类：提供登录界面和聊天界面，处理与服务器的通信
 */
public class ChatClient extends JFrame {
    /**
     * 序列化版本号，用于确保反序列化时类的版本兼容性。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    // UI 组件
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private JPanel chatPanel;
    private JTextField nicknameField;
    private JTextField ipField;
    private JTextField portField;
    private JTextArea chatArea;
    private JTextArea inputField;
    private JButton sendButton;
    private JButton enterButton;
    private JButton exitButton;

    // 网络相关
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private MessageListener listenerThread;
    private volatile boolean connected = false; // 连接状态

    /**
     * 构造方法：初始化界面和事件监听
     */
    public ChatClient() {
        initUI();
        initListeners();
        setVisible(true);
    }

    /**
     * 初始化用户界面
     */
    private void initUI() {
        setTitle("全民聊天室客户端");
        setSize(600, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        add(mainPanel, BorderLayout.CENTER);

        // 聊天面板布局
        chatPanel = new JPanel(new BorderLayout());
        // 上方信息区
        JPanel topPanel = createTopPanel();
        chatPanel.add(topPanel, BorderLayout.NORTH);

        // 中间文本区
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatPanel.add(chatScroll, BorderLayout.CENTER);

        // 下方输入区
        JPanel inputPanel = createInputPanel();
        chatPanel.add(inputPanel, BorderLayout.SOUTH);

        mainPanel.add(chatPanel, "CHAT");
        cardLayout.show(mainPanel, "CHAT");

    }

    /**
     * 创建上方信息面板
     * @return 上方信息面板
     */
    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel();
        // 使用 GridBagLayout 布局
        GridBagLayout gridBagLayout = new GridBagLayout();
        topPanel.setLayout(gridBagLayout);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5); // 设置组件间距

        // 第一行：IP、端口、昵称
        gbc.gridy = 0;

        gbc.gridx = 0;
        topPanel.add(new JLabel("服务器IP:"), gbc);

        gbc.gridx = 1;
        ipField = new JTextField("localhost", 10);
        topPanel.add(ipField, gbc);

        gbc.gridx = 2;
        topPanel.add(new JLabel("服务器端口:"), gbc);

        gbc.gridx = 3;
        portField = new JTextField("12345", 5);     //默认端口号12345
        topPanel.add(portField, gbc);

        gbc.gridx = 4;
        topPanel.add(new JLabel("昵称:"), gbc);

        gbc.gridx = 5;
        nicknameField = new JTextField("托尔芬", 10);   //默认昵称“托尔芬”
        topPanel.add(nicknameField, gbc);

        // 第二行：按钮
        gbc.gridy = 1;

        gbc.gridx = 0;
        gbc.gridwidth = 3; // 按钮跨3列
        enterButton = new JButton("进入聊天室");
        topPanel.add(enterButton, gbc);

        gbc.gridx = 3;
        exitButton = new JButton("退出聊天室");
        exitButton.setEnabled(false); // 禁用退出按钮
        topPanel.add(exitButton, gbc);

        return topPanel;
    }

    /**
     * 创建下方输入面板
     * @return 下方输入面板
     */
    private JPanel createInputPanel() {
        JPanel inputPanel = new JPanel(new BorderLayout());

        // 创建多行文本输入区域（3行高度）
        JTextArea messageArea = new JTextArea(3, 20);
        messageArea.setLineWrap(true);  // 自动换行
        messageArea.setWrapStyleWord(true); // 按单词换行

        // 添加滚动条
        JScrollPane scrollPane = new JScrollPane(messageArea);

        // 创建发送按钮
        sendButton = new JButton("发送");
        sendButton.setEnabled(false); // 初始禁用发送按钮

        // 将组件添加到面板
        inputPanel.add(scrollPane, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        // 保留对messageArea的引用，以便发送消息时获取内容
        this.inputField = messageArea; // 注意：这里需要将inputField改为JTextArea类型

        return inputPanel;
    }

    /**
     * 初始化事件监听器
     */
    private void initListeners() {
        enterButton.addActionListener(e -> connectToServer());
        exitButton.addActionListener(e -> disconnectFromServer());
        sendButton.addActionListener(e -> sendMessage());

        // 设置Enter键发送消息
        InputMap inputMap = inputField.getInputMap();
        ActionMap actionMap = inputField.getActionMap();

        // 1. 覆盖默认的Enter键行为（改为发送消息）
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendAction");
        actionMap.put("sendAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        // 2. 设置Shift+Enter为插入换行符
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insertNewline");
        actionMap.put("insertNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                inputField.append("\n");
            }
        });
    }

    /**
     * 连接服务器：创建Socket，进行昵称验证，切换到聊天界面并启动监听线程
     */
    private void connectToServer() {
        String name = nicknameField.getText().trim();
        if (name.isEmpty() || name.equalsIgnoreCase("管理员")) {
            JOptionPane.showMessageDialog(this, "昵称非法！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String ip = ipField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if(port<1024||port>65535){
                JOptionPane.showMessageDialog(this, "不是[1024,65535]之间的动态端口号！", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "端口号非法！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            socket = new Socket(ip, port);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            // 发送昵称给服务器
            writer.println(name);
            // 读取服务器响应
            String response = reader.readLine();
            if ("OK".equals(response)) {
                connected = true;
                enterButton.setEnabled(false);
                exitButton.setEnabled(true);
                sendButton.setEnabled(true);
                // 启动监听服务器消息线程
                listenerThread = new MessageListener();
                listenerThread.start();
            } else {
                // 服务器返回非法，应退出
                JOptionPane.showMessageDialog(this, "昵称非法或已被使用！", "错误", JOptionPane.ERROR_MESSAGE);
                socket.close();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "无法连接到服务器！", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 发送聊天消息到服务器
     */
    private void sendMessage() {
        if (!connected) {
            JOptionPane.showMessageDialog(this, "未连接到服务器！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String msg = inputField.getText().trim();
        if (!msg.isEmpty()) {
            writer.println(msg);
            inputField.setText(""); // 清空输入区域
            inputField.requestFocusInWindow(); // 自动聚焦到输入框
        }
    }

    /**
     * 断开与服务器的连接
     */
    private void disconnectFromServer() {
        if (connected) {
            try {
                // 关闭资源
                if (socket != null) socket.close();
                if (reader != null) reader.close();
                if (writer != null) writer.close();
            } catch (IOException e) {
                System.err.println("断开连接时发生 IO 异常: " + e.getMessage());
            } finally {
                connected = false;
                enterButton.setEnabled(true);
                exitButton.setEnabled(false);
                sendButton.setEnabled(false);
                // 清空聊天区并返回初始界面
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("");
                    cardLayout.show(mainPanel, "CHAT");
                });
            }
        }
    }

    /**
     * 监听服务器消息的线程：不断读取并显示在聊天区域
     */
    class MessageListener extends Thread {
        @Override
        public void run() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String message = line;
                    SwingUtilities.invokeLater(() -> chatArea.append(message + "\n"));
                }
            } catch (IOException e) {
                // 连接异常
            } finally {
                // 服务器关闭或连接断开时执行
                if (connected) {
                    connected = false;
                    SwingUtilities.invokeLater(() -> {
                        enterButton.setEnabled(true);
                        exitButton.setEnabled(false);
                        sendButton.setEnabled(false);
                        JOptionPane.showMessageDialog(ChatClient.this, "与服务器断开连接！", "提示", JOptionPane.INFORMATION_MESSAGE);
                        // 清空聊天区并返回登录界面
                        chatArea.setText("");
                        cardLayout.show(mainPanel, "CHAT");
                    });
                }
                try {
                    if (socket != null) socket.close();
                } catch (IOException e) {
                    System.err.println("关闭 socket 时发生 IO 异常: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 主函数：启动客户端GUI
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(ChatClient::new);
    }
}