/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2012 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package imagej.ui.swing.display;

import imagej.ImageJ;
import imagej.data.Dataset;
import imagej.data.DatasetService;
import imagej.data.display.DataView;
import imagej.data.display.DatasetView;
import imagej.data.display.ImageCanvas;
import imagej.data.display.ImageDisplay;
import imagej.data.display.ImageDisplayService;
import imagej.data.display.OverlayView;
import imagej.data.display.event.DataViewDeselectedEvent;
import imagej.data.display.event.DataViewSelectedEvent;
import imagej.data.display.event.MouseCursorEvent;
import imagej.data.display.event.PanZoomEvent;
import imagej.event.EventHandler;
import imagej.event.EventService;
import imagej.event.EventSubscriber;
import imagej.ext.display.event.DisplayDeletedEvent;
import imagej.ext.tool.Tool;
import imagej.ext.tool.ToolService;
import imagej.ext.tool.event.ToolActivatedEvent;
import imagej.log.LogService;
import imagej.thread.ThreadService;
import imagej.ui.common.awt.AWTCursors;
import imagej.ui.common.awt.AWTInputEventDispatcher;
import imagej.ui.swing.StaticSwingUtils;
import imagej.ui.swing.overlay.FigureCreatedEvent;
import imagej.ui.swing.overlay.IJHotDrawOverlayAdapter;
import imagej.ui.swing.overlay.JHotDrawTool;
import imagej.ui.swing.overlay.OverlayCreatedListener;
import imagej.ui.swing.overlay.ToolDelegator;
import imagej.util.IntCoords;
import imagej.util.RealCoords;
import imagej.util.RealRect;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.Border;

import net.imglib2.RandomAccess;
import net.imglib2.display.ARGBScreenImage;
import net.imglib2.meta.Axes;
import net.imglib2.meta.AxisType;
import net.imglib2.type.numeric.RealType;

import org.jhotdraw.draw.DefaultDrawing;
import org.jhotdraw.draw.DefaultDrawingEditor;
import org.jhotdraw.draw.DefaultDrawingView;
import org.jhotdraw.draw.Drawing;
import org.jhotdraw.draw.DrawingEditor;
import org.jhotdraw.draw.Figure;
import org.jhotdraw.draw.event.FigureSelectionEvent;
import org.jhotdraw.draw.event.FigureSelectionListener;

/**
 * A renderer of an {@link ImageCanvas}, which uses JHotDraw's
 * {@link DefaultDrawingView} component to do most of the work.
 * 
 * @author Curtis Rueden
 * @author Lee Kamentsky
 */
public class JHotDrawImageCanvas extends JPanel implements AdjustmentListener {

	private static final long serialVersionUID = 1L;

	private final SwingImageDisplayViewer displayViewer;
	private final LogService log;

	private final Drawing drawing;
	private final DefaultDrawingView drawingView;
	private final DrawingEditor drawingEditor;
	private final ToolDelegator toolDelegator;

	private final JScrollPane scrollPane;

	private final List<FigureView> figureViews = new ArrayList<FigureView>();

	private final List<EventSubscriber<?>> subscribers;

	public JHotDrawImageCanvas(final SwingImageDisplayViewer displayViewer) {
		this.displayViewer = displayViewer;
		log = getDisplay().getContext().getService(LogService.class);

		drawing = new DefaultDrawing(); // or QuadTreeDrawing?

		drawingView = new DefaultDrawingView();
		drawingView.setDrawing(drawing);

		drawingEditor = new DefaultDrawingEditor();
		drawingEditor.add(drawingView);
		toolDelegator = new ToolDelegator();
		drawingEditor.setTool(toolDelegator);

		scrollPane = new JScrollPane(drawingView);
		setLayout(new BorderLayout());
		add(scrollPane, BorderLayout.CENTER);

		scrollPane.getHorizontalScrollBar().addAdjustmentListener(this);
		scrollPane.getVerticalScrollBar().addAdjustmentListener(this);

		final ImageJ context = getDisplay().getContext();
		final ToolService toolService = context.getService(ToolService.class);
		final Tool activeTool = toolService.getActiveTool();
		activateTool(activeTool);
		final EventService eventService = context.getService(EventService.class);
		subscribers = eventService.subscribe(this);

		drawingView.addFigureSelectionListener(new FigureSelectionListener() {

			@Override
			public void selectionChanged(final FigureSelectionEvent event) {
				onFigureSelectionChanged(event);
			}
		});
		drawingView.addComponentListener(new ComponentAdapter() {

			@Override
			public void componentResized(final ComponentEvent e) {
				syncCanvas();
			}

		});
	}

	protected FigureView getFigureView(final DataView dataView) {
		for (final FigureView figureView : figureViews) {
			if (figureView.getDataView() == dataView) return figureView;
		}
		return null;
	}

	/**
	 * Responds to the JHotDraw figure selection event by selecting and
	 * deselecting views whose state has changed.
	 * 
	 * @param event Event indicating that the figure selections have changed.
	 */
	protected void onFigureSelectionChanged(final FigureSelectionEvent event) {
		final Set<Figure> newSelection = event.getNewSelection();
		final Set<Figure> oldSelection = event.getOldSelection();
		for (final DataView view : getDisplay()) {
			final FigureView figureView = getFigureView(view);
			if (figureView != null) {
				final Figure figure = figureView.getFigure();
				if (newSelection.contains(figure)) {
					view.setSelected(true);
				}
				else if (oldSelection.contains(figure)) {
					view.setSelected(false);
				}
			}
		}
	}

	@EventHandler
	protected void onViewSelected(final DataViewSelectedEvent event) {
		final DataView view = event.getView();
		final FigureView figureView = getFigureView(view);
		if (figureView != null) {
			final Figure figure = figureView.getFigure();
			if (!drawingView.getSelectedFigures().contains(figure)) {
				drawingView.addToSelection(figure);
			}
		}
	}

	@EventHandler
	protected void onViewDeselected(final DataViewDeselectedEvent event) {
		final DataView view = event.getView();
		final FigureView figureView = getFigureView(view);
		if (figureView != null) {
			final Figure figure = figureView.getFigure();
			if (drawingView.getSelectedFigures().contains(figure)) {
				drawingView.removeFromSelection(figure);
			}
		}
	}

	@EventHandler
	protected void onToolActivatedEvent(final ToolActivatedEvent event) {
		final Tool iTool = event.getTool();
		activateTool(iTool);
	}

	@EventHandler
	protected void onEvent(final DisplayDeletedEvent event) {
		if (event.getObject() == getDisplay()) {
			final EventService eventService =
				event.getContext().getService(EventService.class);
			eventService.unsubscribe(subscribers);
		}
	}

	@EventHandler
	protected void onEvent(final PanZoomEvent event) {
		if (event.getCanvas() != getDisplay().getCanvas()) return;
		syncUI();
	}

	@EventHandler
	protected void onEvent(
		@SuppressWarnings("unused") final MouseCursorEvent event)
	{
		drawingView.setCursor(AWTCursors.getCursor(getDisplay().getCanvas()
			.getCursor()));
	}

	void rebuild() {
		for (final DataView dataView : getDisplay()) {
			FigureView figureView = getFigureView(dataView);
			if (figureView == null) {
				if (dataView instanceof DatasetView) {
					figureView =
						new DatasetFigureView(this.displayViewer, (DatasetView) dataView);
				}
				else if (dataView instanceof OverlayView) {
					figureView =
						new OverlayFigureView(this.displayViewer, (OverlayView) dataView);
				}
				else {
					log.error("Don't know how to make a figure view for " +
						dataView.getClass().getName());
					continue;
				}
				figureViews.add(figureView);
			}
		}
		int idx = 0;
		while (idx < figureViews.size()) {
			final FigureView figureView = figureViews.get(idx);
			if (!getDisplay().contains(figureView.getDataView())) {
				figureViews.remove(idx);
				figureView.dispose();
			}
			else {
				idx++;
			}
		}
	}

	void update() {
		for (final FigureView figureView : figureViews) {
			figureView.update();
		}
	}

	protected void activateTool(final Tool iTool) {
		if (iTool instanceof IJHotDrawOverlayAdapter) {
			final IJHotDrawOverlayAdapter adapter = (IJHotDrawOverlayAdapter) iTool;

			// When the tool creates an overlay, add the
			// overlay/figure combo to a SwingOverlayView.
			final OverlayCreatedListener listener = new OverlayCreatedListener() {

				@SuppressWarnings("synthetic-access")
				@Override
				public void overlayCreated(final FigureCreatedEvent e) {
					final OverlayView overlay = e.getOverlay();
					final ImageDisplay display = getDisplay();
					for (int i = 0; i < display.numDimensions(); i++) {
						final AxisType axis = display.axis(i);
						if (Axes.isXY(axis)) continue;
						if (overlay.getData().getAxisIndex(axis) < 0) {
							overlay.setPosition(display.getLongPosition(axis), axis);
						}
					}
					if (drawingView.getSelectedFigures().contains(e.getFigure())) {
						overlay.setSelected(true);
					}
					final OverlayFigureView figureView =
						new OverlayFigureView(displayViewer, overlay, e.getFigure());
					figureViews.add(figureView);
					display.add(overlay);
					display.update();
				}
			};

			final JHotDrawTool creationTool =
				adapter.getCreationTool(getDisplay(), listener);

			toolDelegator.setCreationTool(creationTool);
		}
		else {
			toolDelegator.setCreationTool(null);
		}
	}

	// -- JHotDrawImageCanvas methods --

	public Drawing getDrawing() {
		return drawing;
	}

	public DefaultDrawingView getDrawingView() {
		return drawingView;
	}

	public DrawingEditor getDrawingEditor() {
		return drawingEditor;
	}

	public void addEventDispatcher(final AWTInputEventDispatcher dispatcher) {
		dispatcher.register(drawingView);
	}

	/**
	 * Captures the current view of data displayed in the canvas, including all
	 * JHotDraw embellishments.
	 */
	public Dataset capture() {
		final ImageDisplay display = getDisplay();
		if (display == null) return null;
		final ImageDisplayService dispSrv =
			display.getContext().getService(ImageDisplayService.class);
		final DatasetView dsView = dispSrv.getActiveDatasetView(display);
		if (dsView == null) return null;

		final ARGBScreenImage screenImage = dsView.getScreenImage();
		final Image pixels = screenImage.image();

		final int w = pixels.getWidth(null);
		final int h = pixels.getHeight(null);

		// draw the backdrop image info
		final BufferedImage outputImage =
			new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D outputGraphics = outputImage.createGraphics();
		outputGraphics.drawImage(pixels, 0, 0, null);

		// draw the overlay info
		for (final FigureView view : figureViews) {
			view.getFigure().draw(outputGraphics);
		}

		// create a dataset that has view data with overlay info on top
		final DatasetService dss =
			display.getContext().getService(DatasetService.class);
		final Dataset dataset =
			dss.create(new long[] { w, h, 3 }, "Captured view", new AxisType[] {
				Axes.X, Axes.Y, Axes.CHANNEL }, 8, false, false);
		dataset.setRGBMerged(true);
		final RandomAccess<? extends RealType<?>> accessor =
			dataset.getImgPlus().randomAccess();
		for (int x = 0; x < w; x++) {
			accessor.setPosition(x, 0);
			for (int y = 0; y < h; y++) {
				accessor.setPosition(y, 1);
				final int rgb = outputImage.getRGB(x, y);
				final int r = (rgb >> 16) & 0xff;
				final int g = (rgb >> 8) & 0xff;
				final int b = (rgb >> 0) & 0xff;
				accessor.setPosition(0, 2);
				accessor.get().setReal(r);
				accessor.setPosition(1, 2);
				accessor.get().setReal(g);
				accessor.setPosition(2, 2);
				accessor.get().setReal(b);
			}
		}
		return dataset;
	}

	// -- JComponent methods --

	@Override
	public Dimension getPreferredSize() {
		// CTR FIXME: Rework this method.

		final Dimension drawViewSize = drawingView.getPreferredSize();

		final Border border = scrollPane.getBorder();
		Dimension slop = new Dimension(0, 0);
		if (border != null) {
			final Insets insets = border.getBorderInsets(scrollPane);
			slop =
				new Dimension(insets.left + insets.right, insets.top + insets.bottom);
		}

		final Dimension bigDrawViewSize =
			new Dimension(drawViewSize.width + slop.width + 1, drawViewSize.height +
				slop.height + 1);

		if (drawViewSize.height == 0 || drawViewSize.width == 0) {
			// The image figure hasn't been placed yet. Calculate the projected size.
			final Rectangle bounds = StaticSwingUtils.getWorkSpaceBounds();
			final RealRect imageBounds = getDisplay().getPlaneExtents();
			final double zoomFactor = getDisplay().getCanvas().getZoomFactor();
			return new Dimension(Math.min((int) (imageBounds.width * zoomFactor) +
				slop.width + 1, bounds.width), Math.min(
				(int) (imageBounds.height * zoomFactor) + slop.width + 1,
				bounds.height));
		}
		if (bigDrawViewSize.width <= scrollPane.getPreferredSize().width &&
			bigDrawViewSize.height <= scrollPane.getPreferredSize().height)
		{
			return bigDrawViewSize;
		}

		// HACK: Size the canvas one pixel larger. This is a workaround to an
		// apparent bug in JHotDraw, where an ImageFigure is initially drawn as a
		// large X until it is finished being rendered. Unfortunately, the X is
		// slightly smaller than the image after being rendered.
		final int w = scrollPane.getPreferredSize().width + 1;
		final int h = scrollPane.getPreferredSize().height + 1;

		return new Dimension(w, h);
	}

	// -- AdjustmentListener methods --

	@Override
	public void adjustmentValueChanged(final AdjustmentEvent e) {
		syncCanvas();
	}

	// -- Helper methods --

	private ImageDisplay getDisplay() {
		return displayViewer.getDisplay();
	}

	/** Updates the {@link ImageCanvas} to match the UI. */
	private void syncCanvas() {
		sync(true);
	}

	/** Updates the UI to match the {@link ImageCanvas}. */
	private void syncUI() {
		sync(false);
	}

	private void sync(final boolean updateCanvas) {
		final ImageCanvas canvas = getDisplay().getCanvas();

		// threading sanity check
		final ThreadService threadService =
			getDisplay().getContext().getService(ThreadService.class);
		if (!threadService.isDispatchThread()) {
			throw new IllegalStateException("Cannot sync viewport from thread: " +
				Thread.currentThread().getName());
		}

		// get UI settings
		final Dimension uiSize = scrollPane.getViewport().getExtentSize();
		final double uiZoom = drawingView.getScaleFactor();
		final Point uiOffset = scrollPane.getViewport().getViewPosition();

		// get canvas settings
		final int canvasWidth = canvas.getViewportWidth();
		final int canvasHeight = canvas.getViewportHeight();
		final double canvasZoom = canvas.getZoomFactor();
		final IntCoords canvasOffset =
			canvas.dataToPanelCoords(new RealCoords(0, 0));
		canvasOffset.x = -canvasOffset.x;
		canvasOffset.y = -canvasOffset.y;

		final boolean sizeChanged =
			uiSize.width != canvasWidth || uiSize.height != canvasHeight;
		final boolean offsetChanged =
			uiOffset.x != canvasOffset.x || uiOffset.y != canvasOffset.y;
		final boolean zoomChanged = uiZoom != canvasZoom;

		if (!sizeChanged && !offsetChanged && !zoomChanged) return;

		if (log.isDebug()) {
			log.debug(getClass().getSimpleName() + " " +
				(updateCanvas ? "syncCanvas: " : "syncUI: ") + "\n\tUI size = " +
				uiSize.width + " x " + uiSize.height + "\n\tUI offset = " +
				uiOffset.x + ", " + uiOffset.y + "\n\tUI zoom = " + uiZoom +
				"\n\tCanvas size = " + canvasWidth + " x " + canvasHeight +
				"\n\tCanvas offset = " + canvasOffset.x + ", " + canvasOffset.y +
				"\n\tCanvas zoom = " + canvasZoom + "\n\t" +
				(sizeChanged ? "sizeChanged " : "") +
				(offsetChanged ? "offsetChanged " : "") +
				(zoomChanged ? "zoomChanged " : ""));
		}

		if (updateCanvas) {
			// sync canvas viewport size
			if (sizeChanged) canvas.setViewportSize(uiSize.width, uiSize.height);

			// sync canvas pan & zoom position
			if (offsetChanged || zoomChanged) {
				// back-compute the center from the origin
				final double panCenterX = (uiOffset.x + uiSize.width / 2d) / uiZoom;
				final double panCenterY = (uiOffset.y + uiSize.height / 2d) / uiZoom;
				canvas.setZoom(uiZoom, new RealCoords(panCenterX, panCenterY));
			}

			if (zoomChanged) maybeResizeWindow();
		}
		else { // update UI
			// sync UI viewport size
			if (sizeChanged) {
				final Dimension newViewSize = new Dimension(canvasWidth, canvasHeight);
				scrollPane.getViewport().setViewSize(newViewSize);
			}

			// sync UI zoom factor
			if (zoomChanged) drawingView.setScaleFactor(canvasZoom);

			// sync UI pan position
			if (offsetChanged) {
				final Point newViewPos = new Point(canvasOffset.x, canvasOffset.y);
				scrollPane.getViewport().setViewPosition(newViewPos);
			}
		}
	}

	private void maybeResizeWindow() {
		final Rectangle bounds = StaticSwingUtils.getWorkSpaceBounds();
		final RealRect imageBounds = getDisplay().getPlaneExtents();
		final ImageCanvas canvas = getDisplay().getCanvas();
		final IntCoords topLeft =
			canvas.dataToPanelCoords(new RealCoords(imageBounds.x, imageBounds.y));
		final IntCoords bottomRight =
			canvas.dataToPanelCoords(new RealCoords(imageBounds.x +
				imageBounds.width, imageBounds.y + imageBounds.height));
		if (bottomRight.x - topLeft.x > bounds.width) return;
		if (bottomRight.y - topLeft.y > bounds.height) return;

		displayViewer.getWindow().pack();
	}

}
