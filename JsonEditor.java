import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class JsonEditor {

    public JsonEditor() {}

    private JFrame frame;
    private JsonNode rootJson;
    private JTree tree;
    private DefaultTreeModel treeModel;
    private JButton saveButton, searchButton, findAllButton, deleteButton, undoButton;
    private JTextField searchField;
    private JLabel searchCountLabel;
    private File jsonFile;
    private ObjectMapper mapper = new ObjectMapper();
    private DefaultMutableTreeNode lastFoundNode = null;
    private JsonNode lastSnapshot = null;
    private DefaultListModel<DefaultMutableTreeNode> searchResultsModel;
    private JList<DefaultMutableTreeNode> searchResultsList;

    public JsonEditor(File file) {
        this.jsonFile = file;

        frame = new JFrame("Large JSON Editor - Loading...");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 700);
        frame.setLayout(new BorderLayout());

        JDialog loadingDialog = createProgressDialog("Loading JSON file...");
        loadingDialog.setVisible(true);

        SwingWorker<JsonNode, Void> loader = new SwingWorker<>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                try (FileInputStream fis = new FileInputStream(jsonFile);
                     InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                    return mapper.readTree(reader);
                }
            }

            @Override
            protected void done() {
                loadingDialog.dispose();
                try {
                    rootJson = get();
                    initUI();
                    frame.setVisible(true);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame,
                            "Failed to read file as JSON/UTF-8:\n" + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    frame.dispose();
                }
            }
        };
        loader.execute();
    }

    private JDialog createProgressDialog(String message) {
        JDialog dialog = new JDialog(frame, false);
        dialog.setUndecorated(true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        panel.setBackground(Color.WHITE);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString(message);
        bar.setStringPainted(true);
        panel.add(new JLabel(message, SwingConstants.CENTER), BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        dialog.add(panel);
        dialog.pack();
        dialog.setSize(300, 90);
        dialog.setLocationRelativeTo(null);
        return dialog;
    }

    private void initUI() {
        LazyJsonTreeNode rootNode = new LazyJsonTreeNode(jsonFile.getName(), rootJson);
        rootNode.loadChildren();
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setShowsRootHandles(true);
        tree.setEditable(true);

        tree.setCellEditor(new DefaultTreeCellEditor(tree, (DefaultTreeCellRenderer) tree.getCellRenderer()) {
            @Override
            public boolean isCellEditable(java.util.EventObject event) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return false;
                Object node = path.getLastPathComponent();
                return node instanceof EditableValueNode;
            }
        });

        tree.expandRow(0);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent e) {
                LazyJsonTreeNode node = (LazyJsonTreeNode) e.getPath().getLastPathComponent();
                node.loadChildren();
                treeModel.nodeStructureChanged(node);
            }
            public void treeWillCollapse(TreeExpansionEvent e) {}
        });

        JScrollPane treeScrollPane = new JScrollPane(tree);

        JPanel bottomPanel = new JPanel(new FlowLayout());
        searchField = new JTextField(30);
        searchButton = new JButton("Find First");
        findAllButton = new JButton("Find All");
        deleteButton = new JButton("Delete Selected");
        undoButton = new JButton("Undo Delete");
        saveButton = new JButton("Save");
        searchCountLabel = new JLabel(" ");

        bottomPanel.add(new JLabel("Search:"));
        bottomPanel.add(searchField);
        bottomPanel.add(searchButton);
        bottomPanel.add(findAllButton);
        bottomPanel.add(deleteButton);
        bottomPanel.add(undoButton);
        bottomPanel.add(saveButton);
        bottomPanel.add(searchCountLabel);

        searchResultsModel = new DefaultListModel<>();
        searchResultsList = new JList<>(searchResultsModel);
        searchResultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane resultsScroll = new JScrollPane(searchResultsList);
        resultsScroll.setPreferredSize(new Dimension(300, 0));

        frame.add(treeScrollPane, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);
        frame.add(resultsScroll, BorderLayout.EAST);

        updateTitleWithFileInfo();

        searchButton.addActionListener(e -> searchJson());
        findAllButton.addActionListener(e -> findAllMatches());
        saveButton.addActionListener(e -> saveFile());
        deleteButton.addActionListener(e -> deleteSelectedNode());
        undoButton.addActionListener(e -> undoDelete());

        searchResultsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DefaultMutableTreeNode node = searchResultsList.getSelectedValue();
                if (node != null) {
                    TreePath path = new TreePath(node.getPath());
                    tree.scrollPathToVisible(path);
                    tree.setSelectionPath(path);
                }
            }
        });
    }

    private void updateTitleWithFileInfo() {
        double sizeInMB = jsonFile.length() / (1024.0 * 1024.0);
        String lastModified = new SimpleDateFormat("dd-MMM-yyyy HH:mm").format(new Date(jsonFile.lastModified()));
        frame.setTitle(String.format("Large JSON Editor - %s (%.2f MB, Last Saved: %s)",
                jsonFile.getName(), sizeInMB, lastModified));
    }

    private void deleteSelectedNode() {
        TreePath selectedPath = tree.getSelectionPath();
        if (selectedPath == null) {
            JOptionPane.showMessageDialog(frame, "No node selected for deletion.");
            return;
        }

        Object selected = selectedPath.getLastPathComponent();
        int confirm = JOptionPane.showConfirmDialog(
                frame, "Are you sure you want to delete this node?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        JDialog progressDialog = createProgressDialog("Deleting node...");
        progressDialog.setVisible(true);

        SwingWorker<Boolean, Void> deleter = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    lastSnapshot = rootJson.deepCopy();
                    return removeSelectedNodeFromJson((DefaultMutableTreeNode) selected);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return false;
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                boolean removed = false;
                try { removed = get(); } catch (Exception ignored) {}
                if (!removed) {
                    JOptionPane.showMessageDialog(frame, "Could not remove node from JSON model.");
                    return;
                }

                MutableTreeNode parent = (MutableTreeNode) ((DefaultMutableTreeNode) selected).getParent();
                if (parent != null) treeModel.removeNodeFromParent((MutableTreeNode) selected);

                JOptionPane.showMessageDialog(frame, "Node deleted successfully.");
            }
        };
        deleter.execute();
    }

    private boolean removeSelectedNodeFromJson(DefaultMutableTreeNode selectedNode) {
        if (selectedNode instanceof EditableValueNode editable) {
            JsonNode parentJson = editable.parentNode;
            if (parentJson instanceof ObjectNode obj) {
                obj.remove(editable.key);
                return true;
            } else if (parentJson instanceof ArrayNode arr) {
                if (editable.arrayIndex >= 0 && editable.arrayIndex < arr.size()) {
                    arr.remove(editable.arrayIndex);
                    return true;
                }
            }
            return false;
        }

        if (selectedNode instanceof LazyJsonTreeNode lazySelected) {
            TreeNode parentTreeNode = selectedNode.getParent();
            if (!(parentTreeNode instanceof DefaultMutableTreeNode parentMutable)) return false;

            JsonNode jsonParent = null;
            if (parentMutable instanceof LazyJsonTreeNode lazyParent) jsonParent = lazyParent.jsonNode;
            else if (parentMutable instanceof EditableValueNode editableParent) jsonParent = editableParent.parentNode;
            else jsonParent = rootJson;

            if (jsonParent == null) return false;

            Object userObj = lazySelected.getUserObject();
            String name = userObj != null ? userObj.toString() : lazySelected.toString();

            if (jsonParent instanceof ObjectNode parentObj) {
                if (name != null && !name.startsWith("[") && parentObj.has(name)) {
                    parentObj.remove(name);
                    return true;
                } else {
                    Iterator<Map.Entry<String, JsonNode>> it = parentObj.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> entry = it.next();
                        if (entry.getValue() == lazySelected.jsonNode) {
                            it.remove();
                            return true;
                        }
                    }
                }
            } else if (jsonParent instanceof ArrayNode parentArr) {
                try {
                    if (name != null && name.startsWith("[") && name.endsWith("]")) {
                        String idxStr = name.substring(1, name.length() - 1);
                        int idx = Integer.parseInt(idxStr);
                        if (idx >= 0 && idx < parentArr.size()) {
                            parentArr.remove(idx);
                            return true;
                        }
                    } else {
                        for (int i = 0; i < parentArr.size(); i++) {
                            if (parentArr.get(i) == lazySelected.jsonNode) {
                                parentArr.remove(i);
                                return true;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void undoDelete() {
        if (lastSnapshot == null) {
            JOptionPane.showMessageDialog(frame, "Nothing to undo.");
            return;
        }
        rootJson = lastSnapshot;
        lastSnapshot = null;

        LazyJsonTreeNode rootNode = new LazyJsonTreeNode(jsonFile.getName(), rootJson);
        rootNode.loadChildren();
        treeModel.setRoot(rootNode);
        treeModel.reload();
        JOptionPane.showMessageDialog(frame, "Undo successful.");
    }

    private void saveFile() {
        JDialog savingDialog = createProgressDialog("Saving file...");
        savingDialog.setVisible(true);

        SwingWorker<Void, Void> saver = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (FileOutputStream fos = new FileOutputStream(jsonFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    mapper.writer().writeValue(writer, rootJson);
                }
                return null;
            }

            @Override
            protected void done() {
                savingDialog.dispose();
                updateTitleWithFileInfo();
                JOptionPane.showMessageDialog(frame, "File saved successfully!");
            }
        };
        saver.execute();
    }

    // --- Remaining supporting classes unchanged ---

    static class LazyJsonTreeNode extends DefaultMutableTreeNode {
        private boolean loaded = false;
        private final JsonNode jsonNode;

        public LazyJsonTreeNode(String name, JsonNode jsonNode) {
            super(name);
            this.jsonNode = jsonNode;
        }

        @Override
        public boolean isLeaf() {
            return jsonNode == null || jsonNode.isValueNode();
        }

        @Override
        public int getChildCount() {
            if (!loaded) loadChildren();
            return super.getChildCount();
        }

        public void loadChildren() {
            if (loaded || jsonNode == null) return;
            loaded = true;
            removeAllChildren();

            if (jsonNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode value = entry.getValue();
                    if (value == null || value.isValueNode() || value.isNull())
                        add(new EditableValueNode(entry.getKey(), jsonNode, false, -1));
                    else
                        add(new LazyJsonTreeNode(entry.getKey(), value));
                }
            } else if (jsonNode.isArray()) {
                for (int i = 0; i < jsonNode.size(); i++) {
                    JsonNode value = jsonNode.get(i);
                    if (value == null || value.isValueNode() || value.isNull())
                        add(new EditableValueNode("", jsonNode, true, i));
                    else
                        add(new LazyJsonTreeNode("[" + i + "]", value));
                }
            } else if (jsonNode.isValueNode()) {
                add(new DefaultMutableTreeNode(EditableValueNode.getNodeText(jsonNode)));
            }
        }
    }

    private void searchJson() {
        String query = searchField.getText().toLowerCase();
        if (query.isEmpty()) return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Queue<DefaultMutableTreeNode> queue = new LinkedList<>();
        queue.add(root);
        boolean foundNext = false;
        boolean startSearch = (lastFoundNode == null);
        while (!queue.isEmpty()) {
            DefaultMutableTreeNode node = queue.poll();
            if (node instanceof LazyJsonTreeNode) {
                ((LazyJsonTreeNode) node).loadChildren();
                treeModel.nodeStructureChanged(node);
            }
            if (!startSearch) {
                if (node == lastFoundNode) startSearch = true;
                continue;
            }
            if (node.toString().toLowerCase().contains(query)) {
                TreePath path = new TreePath(node.getPath());
                tree.scrollPathToVisible(path);
                tree.setSelectionPath(path);
                lastFoundNode = node;
                foundNext = true;
                break;
            }
            for (int i = 0; i < node.getChildCount(); i++)
                queue.add((DefaultMutableTreeNode) node.getChildAt(i));
        }
        if (!foundNext) {
            if (lastFoundNode != null) {
                lastFoundNode = null;
                searchJson();
            } else {
                JOptionPane.showMessageDialog(frame, "No matches found.");
            }
        }
    }

    private void findAllMatches() {
        String query = searchField.getText().toLowerCase();
        if (query.isEmpty()) return;
        searchResultsModel.clear();
        searchCountLabel.setText("Searching...");
        SwingWorker<Void, DefaultMutableTreeNode> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
                Queue<DefaultMutableTreeNode> queue = new LinkedList<>();
                queue.add(root);
                while (!queue.isEmpty() && !isCancelled()) {
                    DefaultMutableTreeNode node = queue.poll();
                    if (node instanceof LazyJsonTreeNode) {
                        ((LazyJsonTreeNode) node).loadChildren();
                        SwingUtilities.invokeLater(() -> treeModel.nodeStructureChanged(node));
                    }
                    if (node.toString().toLowerCase().contains(query)) {
                        publish(node);
                    }
                    for (int i = 0; i < node.getChildCount(); i++)
                        queue.add((DefaultMutableTreeNode) node.getChildAt(i));
                }
                return null;
            }

            @Override
            protected void process(List<DefaultMutableTreeNode> chunks) {
                for (DefaultMutableTreeNode node : chunks)
                    searchResultsModel.addElement(node);
            }

            @Override
            protected void done() {
                searchCountLabel.setText("Matches found: " + searchResultsModel.size());
                if (searchResultsModel.isEmpty())
                    JOptionPane.showMessageDialog(frame, "No matches found.");
                else
                    searchResultsList.setSelectedIndex(0);
            }
        };
        worker.execute();
    }

    public static void main(String[] args) {
        File file;
        if (args.length == 0) {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON/TXT Files", "json", "txt"));
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) file = chooser.getSelectedFile();
            else return;
        } else {
            file = new File(args[0]);
        }
        if (!file.exists()) {
            JOptionPane.showMessageDialog(null, "File not found: " + file.getAbsolutePath());
            return;
        }
        SwingUtilities.invokeLater(() -> new JsonEditor(file));
    }

    static class EditableValueNode extends DefaultMutableTreeNode {
        private final String key;
        private final JsonNode parentNode;
        private final boolean isArrayElement;
        private final int arrayIndex;

        public EditableValueNode(String key, JsonNode parentNode, boolean isArrayElement, int arrayIndex) {
            super(isArrayElement
                    ? ("[" + arrayIndex + "]: " + getNodeText(parentNode.get(arrayIndex)))
                    : (key + ": " + getNodeText(parentNode instanceof ObjectNode ? ((ObjectNode) parentNode).get(key) : null)));
            this.key = key;
            this.parentNode = parentNode;
            this.isArrayElement = isArrayElement;
            this.arrayIndex = arrayIndex;
        }

        @Override
        public boolean isLeaf() { return true; }

        @Override
        public void setUserObject(Object userObject) {
            String str = userObject.toString();
            String newValue = str.contains(":") ? str.substring(str.indexOf(":") + 1).trim() : str.trim();
            JsonNode valueNode;
            if (newValue.matches("-?\\d+")) valueNode = new IntNode(Integer.parseInt(newValue));
            else if (newValue.equalsIgnoreCase("true") || newValue.equalsIgnoreCase("false"))
                valueNode = BooleanNode.valueOf(Boolean.parseBoolean(newValue));
            else if (newValue.equalsIgnoreCase("null")) valueNode = NullNode.getInstance();
            else valueNode = new TextNode(newValue);

            if (isArrayElement && parentNode instanceof ArrayNode)
                ((ArrayNode) parentNode).set(arrayIndex, valueNode);
            else if (parentNode instanceof ObjectNode)
                ((ObjectNode) parentNode).set(key, valueNode);

            super.setUserObject(isArrayElement
                    ? ("[" + arrayIndex + "]: " + getNodeText(valueNode))
                    : (key + ": " + getNodeText(valueNode)));
        }

        static String getNodeText(JsonNode node) {
            if (node == null || node.isNull()) return "null";
            if (node.isTextual()) return "\"" + node.asText() + "\"";
            return node.toString();
        }
    }
}