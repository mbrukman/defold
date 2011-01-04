package com.dynamo.cr.contenteditor.editors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.media.opengl.GL;
import javax.media.opengl.GLContext;
import javax.media.opengl.GLDrawableFactory;
import javax.media.opengl.GLException;
import javax.media.opengl.glu.GLU;
import javax.vecmath.Matrix3d;
import javax.vecmath.Matrix4d;
import javax.vecmath.Quat4d;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector4d;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.IOperationApprover;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.core.commands.operations.UndoContext;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.opengl.GLCanvas;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.operations.LinearUndoViolationUserApprover;
import org.eclipse.ui.operations.RedoActionHandler;
import org.eclipse.ui.operations.UndoActionHandler;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.openmali.vecmath2.Point3d;

import com.dynamo.cr.contenteditor.Activator;
import com.dynamo.cr.contenteditor.manipulator.IManipulator;
import com.dynamo.cr.contenteditor.manipulator.ManipulatorController;
import com.dynamo.cr.contenteditor.scene.CollectionLoader;
import com.dynamo.cr.contenteditor.scene.ISceneListener;
import com.dynamo.cr.contenteditor.scene.MeshLoader;
import com.dynamo.cr.contenteditor.scene.ModelLoader;
import com.dynamo.cr.contenteditor.scene.Node;
import com.dynamo.cr.contenteditor.scene.PrototypeLoader;
import com.dynamo.cr.contenteditor.scene.Scene;
import com.dynamo.cr.contenteditor.scene.SceneEvent;
import com.dynamo.cr.contenteditor.scene.ScenePropertyChangedEvent;
import com.dynamo.cr.contenteditor.util.GLUtil;

public class CollectionEditor extends EditorPart implements IEditor, Listener, MouseListener, MouseMoveListener, SelectionListener, KeyListener, ISceneListener, ISelectionProvider {

    private GLCanvas m_Canvas;
    private GLContext m_Context;
    private final int[] m_ViewPort = new int[4];
    private Camera m_PerspCamera = new Camera(Camera.Type.PERSPECTIVE);
    private Camera m_OrthoCamera = new Camera(Camera.Type.ORTHOGRAPHIC);
    private Camera m_ActiveCamera = m_PerspCamera;
    private CameraController m_CameraController = new CameraController();
    private ResourceLoaderFactory factory;
    private Node m_Root;
    private EditorOutlinePage m_OutlinePage;
    private IntBuffer m_SelectBuffer;

    private Node[] m_SelectedNodes = new Node[0];
    private UndoContext m_UndoContext;
    private int m_CleanUndoStackDepth = 0;
    private boolean m_Dirty = false;

    private ManipulatorController m_ManipulatorController;
    private Map<String, IAction> m_Actions = new HashMap<String, IAction>();
    private Scene m_Scene;

    private RectangleSelectController m_RectangleSelectController = new RectangleSelectController();

    private List<ISelectionChangedListener> m_Listeners = new ArrayList<ISelectionChangedListener>();
    private Node pasteTarget = null;

    private boolean isRendering;

    public CollectionEditor() {
        factory = new ResourceLoaderFactory();
        factory.addLoader(new CollectionLoader(), "collection");
        factory.addLoader(new PrototypeLoader(), "go");
        factory.addLoader(new ModelLoader(), "model");
        factory.addLoader(new MeshLoader(), "dae");

        m_SelectBuffer = ByteBuffer.allocateDirect(4 * MAX_MODELS).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    @Override
    public void dispose() {
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Object getAdapter(Class adapter)
    {
        if (adapter == IContentOutlinePage.class && m_Root != null)
        {
            if (m_OutlinePage == null)
                m_OutlinePage = new EditorOutlinePage(this);
            return m_OutlinePage;
        }
        else
        {
            return super.getAdapter(adapter);
        }
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        IFileEditorInput i = (IFileEditorInput) getEditorInput();
        try
        {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            factory.save(monitor, i.getName(), m_Root, stream);
            i.getFile().setContents(new ByteArrayInputStream(stream.toByteArray()), false, true, monitor);

            IOperationHistory history = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();
            m_CleanUndoStackDepth = history.getUndoHistory(m_UndoContext).length;
            m_Dirty = false;
            firePropertyChange(PROP_DIRTY);

        } catch (Throwable e)
        {
            Status status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, 0, e.getMessage(), null);
            ErrorDialog.openError(Display.getCurrent().getActiveShell(), "Unable to save file", "Unable to save file", status);
        }
    }

    @Override
    public void doSaveAs() {
        // TODO Auto-generated method stub
    }

    class LoaderRunnable implements IRunnableWithProgress {

        String path;
        Throwable exception;
        private Node node;

        LoaderRunnable(String path) {
            this.path = path;
        }

        @Override
        public void run(IProgressMonitor monitor)
                throws InvocationTargetException, InterruptedException {
            try {
                node = factory.load(monitor, m_Scene, path);
            } catch (Throwable e) {
                this.exception = e;
                e.printStackTrace();
            }
        }

    }

    @Override
    public void init(IEditorSite site, IEditorInput input)
            throws PartInitException {
        setSite(site);
        setInput(input);
        setPartName(input.getName());

        IFileEditorInput i = (IFileEditorInput) input;

        try
        {
            boolean found = factory.findContentRoot(i.getFile());

            if (found) {
                m_Scene = new Scene();
                IProgressService service = PlatformUI.getWorkbench().getProgressService();
                LoaderRunnable runnable = new LoaderRunnable(i.getFile().getFullPath().toPortableString());
                service.runInUI(service, runnable, null);
                if (runnable.exception != null)
                    throw runnable.exception;

                m_Root = runnable.node;
                m_Scene.addSceneListener(this);

            }
            else {
                throw new PartInitException(String.format("Unable to find game.project file. Required as it defines the project root folder."));
            }

        } catch (Throwable e)
        {
            e.printStackTrace();
            throw new PartInitException(String.format("Unable to load prototype (%s)", e.getMessage()), e);
        }

        m_ManipulatorController = new ManipulatorController(this);
        m_ManipulatorController.setManipulator("move");
    }

    @Override
    public boolean isDirty() {
        return m_Dirty;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NO_REDRAW_RESIZE);

        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        layout.horizontalSpacing = 0;

        container.setLayout(layout);

        GLData data = new GLData ();
        data.doubleBuffer = true;
        data.depthSize = 24;

        m_Canvas = new GLCanvas(container, SWT.NO_REDRAW_RESIZE, data);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = SWT.DEFAULT;
        gd.heightHint = SWT.DEFAULT;
        m_Canvas.setLayoutData(gd);

        m_Canvas.setCurrent();

        m_Context = GLDrawableFactory.getFactory().createExternalGLContext();

        m_Context.makeCurrent();
        GL gl = m_Context.getGL();
        gl.glClearDepth(1.0f);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LEQUAL);
        gl.glHint(GL.GL_PERSPECTIVE_CORRECTION_HINT, GL.GL_NICEST);

        m_Context.release();

        m_Canvas.addListener(SWT.Resize, this);
        m_Canvas.addMouseListener(this);
        m_Canvas.addMouseMoveListener(this);
        m_Canvas.addKeyListener(this);

        m_UndoContext = new UndoContext();
        IOperationHistory history = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();
        // TODO: Really?
        history.setLimit(m_UndoContext, 100);

        IOperationApprover approver = new LinearUndoViolationUserApprover(m_UndoContext, this);
        history.addOperationApprover(approver);

        // TODO:
        m_Actions.put(ActionFactory.UNDO.getId(), new UndoActionHandler(this.getEditorSite(), m_UndoContext));
        m_Actions.put(ActionFactory.REDO.getId(), new RedoActionHandler(this.getEditorSite(), m_UndoContext));
        getSite().setSelectionProvider(this);

        IContextService context_service = (IContextService) getSite().getService(IContextService.class);
        context_service.activateContext("com.dynamo.cr.contenteditor.contexts.collectioneditor");

        isRendering = false;
        m_Canvas.addListener(SWT.Paint, new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!isRendering) {
                    isRendering = true;
                    m_Canvas.setCurrent();
                    startRenderLoop();
                }
            }
        });
    }

    public void startRenderLoop() {
        m_Canvas.getDisplay().asyncExec(new Runnable()
        {
            @Override
            public void run()
            {
                if (m_Canvas.isDisposed()) return;
                if (m_Canvas.isVisible()) {
                    doPaint();
                    m_Canvas.getDisplay().asyncExec(this);
                } else {
                    isRendering = false;
                }
            }
        });
    }

    public UndoContext getUndoContext() {
        return m_UndoContext;
    }

    IAction getUndoAction() {
        return m_Actions.get(ActionFactory.UNDO.getId());
    }

    IAction getRedoAction() {
        return m_Actions.get(ActionFactory.REDO.getId());
    }

    public void updateActions()
    {
        IActionBars action_bars = getEditorSite().getActionBars();
        action_bars.updateActionBars();

        for (Entry<String, IAction> e : m_Actions.entrySet())
        {
            action_bars.setGlobalActionHandler(e.getKey(), e.getValue());
        }
    }

    @Override
    public void setFocus() {
        m_Canvas.setFocus();
        updateActions();
    }

    @Override
    public void handleEvent(Event event) {
        if (event.type == SWT.Resize)
        {
            Point size = m_Canvas.getSize();
            float aspect = ((float) size.x) / size.y;
            m_ViewPort[0] = 0;
            m_ViewPort[1] = 0;
            m_ViewPort[2] = size.x;
            m_ViewPort[3] = size.y;

            m_PerspCamera.setAspect(aspect);
            m_PerspCamera.setViewport(0, 0, size.x, size.y);
            m_OrthoCamera.setAspect(aspect);
            m_OrthoCamera.setViewport(0, 0, size.x, size.y);

        }
    }

    private void doPaint() {
        m_Context.makeCurrent();
        GL gl = m_Context.getGL();

        try
        {
            draw(gl);
        }
        catch (Throwable e)
        {
            e.printStackTrace();
        }
        finally
        {
            m_Canvas.swapBuffers();
            m_Context.release();
        }
    }

    protected static void drawNodesRecursively(DrawContext context, Node[] nodes)
    {
        GL gl = context.m_GL;

        Matrix4d t = new Matrix4d();
        for (Node node : nodes)
        {
            gl.glPushMatrix();
            node.getLocalTransform(t);
            GLUtil.multMatrix(gl, t);
            node.draw(context);
            drawNodesRecursively(context, node.getChilden());
            gl.glPopMatrix();
        }
    }

    private static int drawSelectNodesRecursively(DrawContext context, Node[] nodes, int next_name, Map<Integer, Node> name_node_map)
    {
        GL gl = context.m_GL;

        Matrix4d t = new Matrix4d();
        for (Node node : nodes)
        {
            gl.glPushMatrix();

            node.getLocalTransform(t);
            GLUtil.multMatrix(gl, t);
            name_node_map.put(next_name, node);
            gl.glPushName(next_name++);
            node.draw(context);
            gl.glPopName();

            next_name = drawSelectNodesRecursively(context, node.getChilden(), next_name, name_node_map);

            gl.glPopMatrix();
        }

        return next_name;
    }

    private static void drawGrid(DrawContext context, Camera camera) {
        GL gl = context.m_GL;

        gl.glDisable(GL.GL_LIGHTING);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL.GL_LINE);

        gl.glBegin(GL.GL_LINES);

        Matrix4d viewProj = new Matrix4d();
        Matrix4d view = new Matrix4d();
        camera.getProjectionMatrix(viewProj);
        camera.getViewMatrix(view);
        viewProj.mul(view);
        Vector4d p = new Vector4d();
        viewProj.getRow(3, p);
        Vector4d n = new Vector4d();
        Vector4d[] frustumPlanes = new Vector4d[6];
        for (int i = 0; i < 3; ++i) {
            for (int s = 0; s < 2; ++s) {
                viewProj.getRow(i, n);
                n.scale(Math.signum(s - 0.5));
                n.add(p);
                frustumPlanes[i * 2 + s] = new Vector4d(n);
            }
        }
        Vector4d axis = new Vector4d();
        double[] values = new double[4];
        for (int i = 0; i < 3; ++i) {
            Arrays.fill(values, 0.0);
            values[i] = 1.0;
            axis.set(values);
            double min_align = 0.7071;
            n.set(frustumPlanes[5]);
            n.w = 0.0;
            n.normalize();
            double align = Math.abs(axis.dot(n));
            if (align > min_align) {
                Arrays.fill(values, Double.POSITIVE_INFINITY);
                Vector4d aabbMin = new Vector4d(values);
                aabbMin.w = 1.0;
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                Vector4d aabbMax = new Vector4d(values);
                aabbMax.w = 1.0;

                Matrix3d m = new Matrix3d();
                Vector3d v = new Vector3d();

                int[] planeIndices = new int[] {
                        0, 2,
                        0, 3,
                        1, 2,
                        1, 3
                };

                for (int j = 0; j < 4; ++j) {
                    m.setRow(0, axis.x, axis.y, axis.z);
                    v.x = 0.0;

                    p.set(frustumPlanes[planeIndices[j*2 + 0]]);
                    m.setRow(1, p.x, p.y, p.z);
                    v.y = -p.w;

                    p.set(frustumPlanes[planeIndices[j*2 + 1]]);
                    m.setRow(2, p.x, p.y, p.z);
                    v.z = -p.w;

                    m.invert();
                    m.transform(v);

                    aabbMin.x = Math.min(aabbMin.x, v.x);
                    aabbMin.y = Math.min(aabbMin.y, v.y);
                    aabbMin.z = Math.min(aabbMin.z, v.z);

                    aabbMax.x = Math.max(aabbMax.x, v.x);
                    aabbMax.y = Math.max(aabbMax.y, v.y);
                    aabbMax.z = Math.max(aabbMax.z, v.z);
                }

                Vector4d aabbDims = new Vector4d(aabbMax);
                aabbDims.sub(aabbMin);
                aabbDims.get(values);
                double exp = Double.POSITIVE_INFINITY;
                for (int j = 0; j < 3; ++j) {
                    if (i != j) {
                        exp = Math.min(exp, Math.log10(values[j]));
                    }
                }
                double e = Math.floor(exp);
                for (int j = 0; j < 2; ++j) {
                    double alpha = (1.0 - Math.abs(exp - e)) * (align - min_align)/(1.0 - min_align);
                    gl.glColor4d(Constants.GRID_COLOR[0], Constants.GRID_COLOR[1], Constants.GRID_COLOR[2], alpha);

                    double base = Math.pow(10.0, e);

                    double[] minValues = new double[4];
                    aabbMin.get(minValues);
                    double[] maxValues = new double[4];
                    aabbMax.get(maxValues);

                    for (int k = 0; k < 3; ++k) {
                        minValues[k] = Math.floor(minValues[k]/base) * base;
                        maxValues[k] = Math.ceil(maxValues[k]/base) * base;
                    }

                    double step = Math.pow(10.0, e-1);
                    double[] vertex = new double[3];
                    int axis1 = i;
                    int axis2 = (axis1 + 1) % 3;
                    int axis3 = (axis1 + 2) % 3;
                    for (int k = 0; k < 2; ++k) {
                        for (double dv = minValues[axis2]; dv <= maxValues[axis2]; dv += step) {
                            vertex[axis1] = 0.0;
                            vertex[axis2] = dv;
                            vertex[axis3] = minValues[axis3];
                            gl.glVertex3dv(vertex, 0);
                            vertex[axis3] = maxValues[axis3];
                            gl.glVertex3dv(vertex, 0);
                        }
                        int tmp = axis2;
                        axis2 = axis3;
                        axis3 = tmp;
                    }

                    e += 1.0;
                }
            }
        }

        gl.glEnd();

        gl.glDisable(GL.GL_BLEND);
    }

    private static void drawViewTriad(DrawContext context, Camera camera) {
        GL gl = context.m_GL;

        gl.glDisable(GL.GL_DEPTH_TEST);

        int margin = 5;
        int triadSize = 30;
        int[] oldViewport = new int[4];
        camera.getViewport(oldViewport);
        int viewportSize = 2 * margin + 2 * triadSize;
        gl.glViewport(0, 0, viewportSize, viewportSize);

        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        gl.glMatrixMode(GL.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        Matrix4d view = new Matrix4d();
        camera.getViewMatrix(view);
        view.setColumn(3, 0.0, 0.0, 0.0, 1.0);
        GLUtil.multMatrix(gl, view);

        Matrix4d triad_transform = new Matrix4d();
        triad_transform.setIdentity();
        triad_transform.mul((2.0 * triadSize)/viewportSize);
        triad_transform.m33 = 1.0;
        GLUtil.multMatrix(gl, triad_transform);

        GLUtil.drawTriad(gl);

        gl.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPopMatrix();

        gl.glMatrixMode(GL.GL_MODELVIEW);
        gl.glPopMatrix();

        gl.glEnable(GL.GL_DEPTH_TEST);
    }

    private void draw(GL gl) {
        // Setup
        gl.glShadeModel(GL.GL_SMOOTH);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_COLOR_MATERIAL);

        gl.glColorMaterial(GL.GL_FRONT, GL.GL_AMBIENT_AND_DIFFUSE);

        float light_ambient[] =  new float[] { 0.5f, 0.5f, 0.5f, 1.0f };
        float light_diffuse[] =  new float[] { 1.0f, 1.0f, 1.0f, 1.0f };
        float light_position[]=  new float[] { 0.0f, 8.0f, 8.0f, 1.0f };
        Vector4d camera_pos = m_ActiveCamera.getPosition();
        light_position[0] = (float) camera_pos.x;
        light_position[1] = (float) camera_pos.y;
        light_position[2] = (float) camera_pos.z;

        gl.glLightfv(GL.GL_LIGHT0, GL.GL_AMBIENT, light_ambient, 0);
        gl.glLightfv(GL.GL_LIGHT0, GL.GL_DIFFUSE, light_diffuse, 0);
        gl.glLightfv(GL.GL_LIGHT0, GL.GL_POSITION, light_position, 0);

        gl.glEnable(GL.GL_LIGHT0);
        gl.glEnable(GL.GL_LIGHTING);

        float[] bg = Constants.BACKGROUND_COLOR;
        gl.glClearColor(bg[0], bg[1], bg[2], 1);

        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glLoadMatrixd(m_ActiveCamera.getProjectionMatrixArray(), 0);

        gl.glMatrixMode(GL.GL_MODELVIEW);
        gl.glLoadMatrixd(m_ActiveCamera.getViewMatrixArray(), 0);

        gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL.GL_FILL);

        DrawContext context = new DrawContext(gl, m_SelectedNodes);
        drawNodesRecursively(context, m_Root.getChilden());

        // Draw grid
        drawGrid(context, m_ActiveCamera);

        // Draw manipulator
        gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL.GL_FILL);
        gl.glDisable(GL.GL_DEPTH_TEST);
        m_ManipulatorController.draw(gl, false);
        gl.glEnable(GL.GL_DEPTH_TEST);

        m_RectangleSelectController.draw(gl, this);

        // Draw view triad
        drawViewTriad(context, m_ActiveCamera);
    }

    // TODO: ?
    public final static int MAX_MODELS = 5000;

    public void beginSelect(GL gl, double[] view_matrix, int x, int y, int w, int h)
    {
        gl.glSelectBuffer(MAX_MODELS, m_SelectBuffer);
        gl.glRenderMode(GL.GL_SELECT);

        gl.glMatrixMode(GL.GL_PROJECTION);
        float[] projection = new float[16];
        gl.glGetFloatv(GL.GL_PROJECTION_MATRIX, projection, 0);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        int[] viewport = new int[4];
        gl.glGetIntegerv(GL.GL_VIEWPORT, viewport, 0);
        GLU glu = new GLU();
        glu.gluPickMatrix(x, viewport[3] - y, w, h, viewport, 0);
        gl.glMultMatrixf(projection, 0);

        gl.glMatrixMode(GL.GL_MODELVIEW);
        gl.glLoadIdentity();
        gl.glLoadMatrixd(view_matrix, 0);

        gl.glInitNames();
    }

    public static class SelectResult
    {
        static class Pair implements Comparable<Pair> {
            Pair(long z, int index) {
                this.z = z;
                this.index = index;
            }
            long z;
            int index;

            @Override
            public int compareTo(Pair o) {
                return (z<o.z ? -1 : (z==o.z ? 0 : 1));
            }

            @Override
            public String toString() {
                return String.format("%d (%d)", index, z);
            }
        }
        public SelectResult(List<Pair> selected, long minz)
        {
            m_Selected = selected;
            m_MinZ = minz;

        }
        public List<Pair> m_Selected;
        public long m_MinZ = Long.MAX_VALUE;
    }

    private static long toUnsignedInt(int i)
    {
        long tmp = i;
        return ( tmp << 32 ) >>> 32;
    }

    public SelectResult endSelct(GL gl)
    {
        long minz;
        minz = Long.MAX_VALUE;

        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL.GL_MODELVIEW);
        gl.glFlush();
        int hits = gl.glRenderMode(GL.GL_RENDER);

        List<SelectResult.Pair> selected = new ArrayList<SelectResult.Pair>();

        int names, ptr, ptrNames = 0, numberOfNames = 0;
        ptr = 0;
        for (int i = 0; i < hits; i++)
        {
           names = m_SelectBuffer.get(ptr);
           ptr++;
           {
               numberOfNames = names;
               minz = toUnsignedInt(m_SelectBuffer.get(ptr));
               ptrNames = ptr+2;
               selected.add(new SelectResult.Pair(minz, m_SelectBuffer.get(ptrNames)));
           }

           ptr += names+2;
        }
        ptr = ptrNames;

        Collections.sort(selected);

        if (numberOfNames > 0)
        {
            return new SelectResult(selected, minz);
        }
        else
        {
            return new SelectResult(selected, minz);
        }
    }

    public void setSelectedNodes(Node[] nodes)
    {
        // Go upwards in hierarchy until a selectable node is found
        for (int i = 0; i < nodes.length; ++i) {
            Node n  = nodes[i];
            while ((n.getFlags() & Node.FLAG_SELECTABLE) == 0)
            {
                n = n.getParent();
            }
            nodes[i] = n;
        }

        // Remove duplicates. Perhaps not optimal. Loop below does not handle duplicates
        List<Node> node_set = new ArrayList<Node>();
        for (Node n : nodes) {
            if (!node_set.contains(n))
                node_set.add(n);
        }
        nodes = node_set.toArray(new Node[node_set.size()]);

        // Deselect any node child of any other node
        ArrayList<Node> list = new ArrayList<Node>();
        for (int i = 0; i < nodes.length; ++i) {
            boolean select = true;
            for (int j = 0; j < nodes.length; ++j) {
                if (i != j) {
                    if (nodes[i].isChildOf(nodes[j]) || nodes[i] == nodes[j]) {
                        select = false;
                        break;
                    }
                }
            }
            if (select) {
                list.add(nodes[i]);
            }
        }
        nodes = new Node[list.size()];
        list.toArray(nodes);

        m_SelectedNodes = nodes;
        m_ManipulatorController.setSelected(m_SelectedNodes);

        SelectionChangedEvent e;
        if (m_SelectedNodes.length > 0) {
            e = new SelectionChangedEvent(this, new StructuredSelection(m_SelectedNodes));
            pasteTarget = m_SelectedNodes[0];
        }
        else {
            e = new SelectionChangedEvent(this, new StructuredSelection());
            pasteTarget = null;
        }

        for (ISelectionChangedListener l : m_Listeners)
        {
            l.selectionChanged(e);
        }
    }

    public Node[] getSelectedNodes() {
        return m_SelectedNodes;
    }

    private boolean selectManipulator(MouseEvent e) throws GLException
    {
        m_Context.makeCurrent();
        GL gl = m_Context.getGL();

        beginSelect(gl, m_ActiveCamera.getViewMatrixArray(), e.x, e.y, 5, 5);
        m_ManipulatorController.draw(gl, true);
        SelectResult result = endSelct(gl);

        if (result.m_Selected.size() > 0)
            m_ManipulatorController.setActiveHandle(result.m_Selected.get(0).index);
        else
            m_ManipulatorController.setActiveHandle(-1);

        return result.m_Selected.size() > 0;
    }

    public Node[] selectNode(int x, int y, int w, int h, boolean multi_select, boolean add_to_selection, boolean update_ui) throws GLException
    {
        if (w == 0 || h == 0)
            return new Node[0];

        m_Context.makeCurrent();
        GL gl = m_Context.getGL();

        beginSelect(gl, m_ActiveCamera.getViewMatrixArray(), x, y, w, h);
        DrawContext context = new DrawContext(gl, m_SelectedNodes);
        Map<Integer, Node> name_node_map = new HashMap<Integer, Node>();
        drawSelectNodesRecursively(context, m_Root.getChilden(), 0, name_node_map);
        SelectResult result = endSelct(gl);

        List<Node> to_select_list= new ArrayList<Node>();
        if (add_to_selection)
        {
            for (Node n : m_SelectedNodes) {
                to_select_list.add(n);
            }
        }

        if (result.m_Selected.size() > 0)
        {
            for (SelectResult.Pair pair : result.m_Selected) {
                Node n = name_node_map.get(pair.index);

                // Get the object that actually can be selected. Required for test below
                while ((n.getFlags() & Node.FLAG_SELECTABLE) == 0)
                {
                    n = n.getParent();
                }

                assert(n != null);
                if (!to_select_list.contains(n)) {
                    to_select_list.add(n);
                }
                else if (add_to_selection) {
                    to_select_list.remove(n);
                }

                if (!multi_select)
                    break;
            }
        }

        Node[] to_select_array = new Node[to_select_list.size()];
        to_select_list.toArray(to_select_array);

        return to_select_array;
    }

    @Override
    public void mouseDoubleClick(MouseEvent e) {
    }

    @Override
    public void mouseDown(MouseEvent e) {
        m_Canvas.setFocus();
        if (m_CameraController.mouseDown(e))
            return;

        if (selectManipulator(e))
        {
            // A manipulator handle was hit
        }
        else
        {
            // Try selecting a node instead
            Node[] nodes = selectNode(e.x, e.y, 5, 5, false, e.stateMask == SWT.SHIFT, true);
            setSelectedNodes(nodes);

            if (m_SelectedNodes.length == 0)
                m_RectangleSelectController.mouseDown(e, this);
        }
        m_ManipulatorController.mouseDown(e);
    }

    @Override
    public void mouseUp(MouseEvent e) {
        m_CameraController.mouseUp(e);
        m_ManipulatorController.mouseUp(e);
        m_RectangleSelectController.mouseUp(e, this);
    }

    @Override
    public void mouseMove(MouseEvent e) {
        m_CameraController.mouseMove(m_ActiveCamera, e);
        Matrix4d m = new Matrix4d();
        m_ActiveCamera.getViewMatrix(m);
        m_RectangleSelectController.mouseMove(e, this);

        m_ManipulatorController.mouseMove(e);
    }

    public Node getRoot() {
        return m_Root;
    }

    @Override
    public int[] getViewPort() {
        return m_ViewPort;
    }

    @Override
    public void executeOperation(IUndoableOperation operation) {
        IOperationHistory history = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();
        operation.addContext(m_UndoContext);
        IStatus status = null;
        try
        {
            status = history.execute(operation, null, null);

        } catch (ExecutionException e)
        {
            e.printStackTrace();
            // if-clause below will trigger
        }

        if (status != Status.OK_STATUS)
        {
            System.err.println("Failed to execute operation: " + operation);
        }

    }

    @Override
    public void viewToWorld(int x, int y, Vector4d world_point, Vector4d world_vector) {
        EditorUtil.viewToWorld(m_ActiveCamera, x, y, world_point, world_vector);
    }

    @Override
    public double[] worldToView(Point3d point) {
        javax.vecmath.Point3d ret = m_ActiveCamera.project(point.x(), point.y(), point.z());
        return new double[] {ret.x, ret.y};
    }

    @Override
    public Camera getCamera() {
        return m_ActiveCamera;
    }

    @Override
    public void setManipulator(String manipulator)
    {
        m_ManipulatorController.setManipulator(manipulator);
    }

    @Override
    public IManipulator getManipulator() {
        return m_ManipulatorController.getManipulator();
    }

    @Override
    public void widgetSelected(SelectionEvent e) {
        Object data = e.widget.getData();
        if (data == null)
            return;
    }

    @Override
    public void widgetDefaultSelected(SelectionEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.character == ' ') {
            if (m_ActiveCamera == m_PerspCamera) {
                // ortho camera view rotation
                Matrix4d view = new Matrix4d();
                m_PerspCamera.getViewMatrix(view);
                Vector4d v = new Vector4d();
                for (int i = 0; i < 3; ++i) {
                    view.getColumn(i, v);
                    int index = 0;
                    if (Math.abs(v.x) > Math.abs(v.y)) {
                        if (Math.abs(v.x) > Math.abs(v.z)) {
                            v.x = Math.signum(v.x);
                            index = 0;
                        } else {
                            v.z = Math.signum(v.z);
                            index = 2;
                        }
                    } else if (Math.abs(v.y) > Math.abs(v.z)) {
                        v.y = Math.signum(v.y);
                        index = 1;
                    } else {
                        v.z = Math.signum(v.z);
                        index = 2;
                    }
                    if (Math.abs(v.x) < 1.0) v.x = 0.0;
                    if (Math.abs(v.y) < 1.0) v.y = 0.0;
                    if (Math.abs(v.z) < 1.0) v.z = 0.0;
                    if (Math.abs(v.w) < 1.0) v.w = 0.0;
                    view.setRow(index, new Vector4d(0.0, 0.0, 0.0, 0.0));
                    view.setColumn(i, v);
                }
                Quat4d rot = new Quat4d();
                rot.set(view);
                rot.conjugate();
                m_OrthoCamera.setRotation(rot);

                // ortho camera view position
                v.set(m_PerspCamera.getPosition());
                v.sub(m_CameraController.getFocusPoint());
                double length = v.length();
                view.invert();
                view.getColumn(2, v);
                v.scale(length);
                v.add(m_CameraController.getFocusPoint());
                m_OrthoCamera.setPosition(v.x, v.y, v.z);

                // ortho camera fov
                m_PerspCamera.getViewMatrix(view);
                Matrix4d proj = new Matrix4d();
                m_PerspCamera.getProjectionMatrix(proj);
                Matrix4d viewProj = new Matrix4d(proj);
                viewProj.mul(view);
                Matrix4d invViewProj = new Matrix4d(viewProj);
                invViewProj.invert();
                Vector4d left = new Vector4d(m_CameraController.getFocusPoint());
                viewProj.transform(left);
                double device_z = left.z/left.w;
                left.set(-1.0, 0.0, device_z, 1.0);
                invViewProj.transform(left);
                left.scale(1/left.w);
                Vector4d right = new Vector4d(1.0, 0.0, device_z, 1.0);
                invViewProj.transform(right);
                right.scale(1/right.w);
                right.sub(left);
                double width = right.length();
                m_OrthoCamera.setFov(width);

                m_ActiveCamera = m_OrthoCamera;
            } else {
                // perspective view position
                Matrix4d view = new Matrix4d();
                m_OrthoCamera.getViewMatrix(view);
                Matrix4d proj = new Matrix4d();
                m_OrthoCamera.getProjectionMatrix(proj);
                Matrix4d viewProj = new Matrix4d(proj);
                viewProj.mul(view);
                Vector4d right = new Vector4d(1.0, 0.0, 0.0, 1.0);
                Vector4d left = new Vector4d(-1.0, 0.0, 0.0, 1.0);
                Matrix4d invViewProj = new Matrix4d(viewProj);
                invViewProj.invert();
                invViewProj.transform(right);
                invViewProj.transform(left);
                right.sub(left);
                right.scale(0.5);
                m_PerspCamera.getViewMatrix(view);
                m_PerspCamera.getProjectionMatrix(proj);
                right.x = right.length();
                right.y = 0.0;
                right.z = 0.0;
                right.w = 0.0;
                proj.transform(right);

                Matrix4d invView = new Matrix4d(view);
                invView.invert();
                Vector4d pos = new Vector4d();
                invView.getColumn(2, pos);
                pos.scale(right.x);
                pos.add(m_CameraController.getFocusPoint());
                m_PerspCamera.setPosition(pos.x, pos.y, pos.z);

                m_ActiveCamera = m_PerspCamera;
            }
        }
        else
        {
            m_ManipulatorController.keyPressed(e);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    void updateDirtyStateAsync() {
        /*
         * We need to postpone dirty check as the undo operation can
         * still be active at this point. (Triggered from a method in IUndoableOperation)
         * TODO: This is called for every movement... A lot of calls when
         * moving an object.
         */
        Display display = getSite().getShell().getDisplay();
        display.asyncExec(new Runnable()
        {
            @Override
            public void run()
            {
                IOperationHistory history = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();

                m_Dirty = history.getUndoHistory(m_UndoContext).length != m_CleanUndoStackDepth;
                firePropertyChange(PROP_DIRTY);

                if (m_OutlinePage != null)
                    m_OutlinePage.refresh();
            }
        });
    }

    @Override
    public void sceneChanged(SceneEvent event) {

        updateDirtyStateAsync();
        if (event.m_Type == SceneEvent.NODE_REMOVED) {
            setSelectedNodes(new Node[] {});
        }
    }

    @Override
    public void propertyChanged(ScenePropertyChangedEvent event) {
        updateDirtyStateAsync();
    }

    // SelectionProvider
    @Override
    public void addSelectionChangedListener(ISelectionChangedListener listener) {
        m_Listeners.add(listener);
    }

    @Override
    public ISelection getSelection() {
        if (m_SelectedNodes.length > 0)
            return new StructuredSelection(m_SelectedNodes);
        else
            return new StructuredSelection();
    }

    @Override
    public void removeSelectionChangedListener(
            ISelectionChangedListener listener) {
        m_Listeners.remove(listener);
    }

    @Override
    public void setSelection(ISelection selection) {
        if (selection instanceof IStructuredSelection)
        {
            IStructuredSelection s = (IStructuredSelection) selection;
            if (!s.isEmpty())
            {
                ArrayList<Node> list = new ArrayList<Node>();
                @SuppressWarnings("rawtypes")
                Iterator iter = s.iterator();
                while (iter.hasNext()) {
                    Object o = iter.next();
                    if (o instanceof Node)
                    {
                        Node node = (Node) o;
                        list.add(node);
                    }
                }
                Node[] nodes = new Node[list.size()];
                list.toArray(nodes);
                setSelectedNodes(nodes);
            }
        }
    }

    @Override
    public Scene getScene() {
        return m_Scene;
    }

    @Override
    public ResourceLoaderFactory getLoaderFactory() {
        return factory;
    }

    @Override
    public Node getPasteTarget() {
        return pasteTarget;
    }

    @Override
    public void setPasteTarget(Node node) {
        pasteTarget = node;
    }

    @Override
    public boolean isSelecting() {
        return m_RectangleSelectController.isSelecting();
    }
}
