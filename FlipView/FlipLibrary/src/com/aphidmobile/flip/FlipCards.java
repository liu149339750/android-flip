/*
Copyright 2012 Aphid Mobile

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
 
	 http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

 */

package com.aphidmobile.flip;

import android.view.*;
import com.aphidmobile.utils.AphidLog;

import javax.microedition.khronos.opengles.GL10;

public class FlipCards {
	private static final float ACCELERATION = 0.618f;
	private static final float TIP_SPEED = 1f;
	private static final float MOVEMENT_RATE = 1.5f;
	private static final int MAX_TIP_ANGLE = 60;

	private static final int STATE_TIP = 0;
	private static final int STATE_TOUCH = 1;
	private static final int STATE_AUTO_ROTATE = 2;

	private ViewDualCards frontCards;
	private ViewDualCards backCards;

	private float angle = 0f;
	private boolean forward = true;
	private int animatedFrame = 0;
	private int state = STATE_TIP;

	private float lastY = -1;

	private VelocityTracker velocityTracker;
	private FlipViewController controller;

	private int activeIndex = -1;

	public FlipCards(FlipViewController controller) {
		this.controller = controller;

		frontCards = new ViewDualCards();
		backCards = new ViewDualCards();

		resetAxises();
	}

	public void reloadTexture(int frontIndex, View frontView, int backIndex, View backView) {
		synchronized (this) {
			if (frontView != null) {
				if (backCards.getView() == frontView) {
					frontCards.setView(-1, null);
					swapCards();
				}
			}

			if (backView != null) {
				if (frontCards.getView() == backView) {
					backCards.setView(-1, null);
					swapCards();
				}
			}

			boolean frontChanged = frontCards.setView(frontIndex, frontView);
			boolean backChanged = backCards.setView(backIndex, backView);

			AphidLog.i("reloading texture: %s and %s; old views: %s, %s, front changed %s, back changed %s", frontView, backView, frontCards.getView(), backCards.getView(), frontChanged, backChanged);

//			if (frontChanged || backChanged || true) {
			if (frontIndex == activeIndex) {
				if (angle >= 180)
					angle -= 180;
				else if (angle < 0)
					angle += 180;
			} else if (backIndex == activeIndex) {
				if (angle < 0)
					angle += 180;
			}

			AphidLog.i("View changed: front (%d, %s), back (%d, %s), angle %s, activeIndex %d", frontIndex, frontView, backIndex, backView, angle, activeIndex);
		}
//		}
	}

	private void swapCards() {
		ViewDualCards tmp = frontCards;
		frontCards = backCards;
		backCards = tmp;
		resetAxises();
	}

	public void rotateBy(float delta) {
		angle += delta;

		if (backCards.getIndex() == -1) {
			if (angle >= MAX_TIP_ANGLE)
				angle = MAX_TIP_ANGLE;
		}

		if (angle > 180)
			angle = 180;
		else if (angle < 0)
			angle = 0;
	}

	public void setState(int state) {
		if (this.state != state) {
			this.state = state;
			animatedFrame = 0;
		}
	}

	public synchronized void draw(FlipRenderer renderer, GL10 gl) {
		applyTexture(renderer, gl);

		if (!Utils.isValidTexture(frontCards.getTexture()) && !Utils.isValidTexture(backCards.getTexture()))
			return;

		switch (state) {
			case STATE_TIP: {
				if (false) { //XXX: debug only
					if (angle >= 180)
						forward = false;
					else if (angle <= 0)
						forward = true;

					rotateBy((forward ? TIP_SPEED : -TIP_SPEED));
					if (angle > 90 && angle <= 180 - MAX_TIP_ANGLE) {
						forward = true;
					} else if (angle < 90 && angle >= MAX_TIP_ANGLE) {
						forward = false;
					}
				}
			}
			break;
			case STATE_TOUCH:
				break;
			case STATE_AUTO_ROTATE: {
				animatedFrame++;
				rotateBy((forward ? ACCELERATION : -ACCELERATION) * animatedFrame);
				if (angle >= 180 || angle <= 0) {
					setState(STATE_TIP);
					if (angle >= 180) { //flip to next page
						if (backCards.getIndex() != -1) {
							activeIndex = backCards.getIndex();
							controller.postFlippedToView(activeIndex);
						} else
							angle = 180;
					}

					controller.postHideFlipAnimation();
				}
			}
			break;
			default:
				AphidLog.e("Invalid state: " + state);
				break;
		}

		if (angle < 90) { //render front view over back view
			frontCards.getTopCard().setAngle(0);
			frontCards.getTopCard().draw(gl);

			backCards.getBottomCard().setAngle(0);
			backCards.getBottomCard().draw(gl);

			frontCards.getBottomCard().setAngle(angle);
			frontCards.getBottomCard().draw(gl);
		} else { //render back view first
			frontCards.getTopCard().setAngle(0);
			frontCards.getTopCard().draw(gl);

			backCards.getTopCard().setAngle(180 - angle);
			backCards.getTopCard().draw(gl);

			backCards.getBottomCard().setAngle(0);
			backCards.getBottomCard().draw(gl);
		}
	}

	private void applyTexture(FlipRenderer renderer, GL10 gl) {
		frontCards.buildTexture(renderer, gl);
		backCards.buildTexture(renderer, gl);
	}

	public void invalidateTexture() {
		frontCards.abandonTexture();
		backCards.abandonTexture();
	}

	public synchronized boolean handleTouchEvent(MotionEvent event, boolean isOnTouchEvent) {
		if (!Utils.isValidTexture(frontCards.getTexture()) && !Utils.isValidTexture(backCards.getTexture()))
			return false;

		float delta;

		Texture texture = frontCards.getTexture();
		if (texture == null)
			texture = backCards.getTexture();

		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				lastY = event.getY();
				return isOnTouchEvent;
			case MotionEvent.ACTION_MOVE:
				delta = lastY - event.getY();
				if (Math.abs(delta) > controller.getTouchSlop())
					setState(STATE_TOUCH); //XXX: initialize views?
				if (state == STATE_TOUCH) {
					controller.showFlipAnimation();
					
					final float angleDelta = 180 * delta / texture.getContentHeight() * MOVEMENT_RATE;
					angle += angleDelta;
					if (backCards.getIndex() == -1) {
						if (angle >= MAX_TIP_ANGLE)
							angle = MAX_TIP_ANGLE;
					} else if (backCards.getIndex() == 0) {
						if (angle <= 180 - MAX_TIP_ANGLE)
							angle = 180 - MAX_TIP_ANGLE;
					}
					if (angle < 0) {
						if (frontCards.getIndex() > 0) {
							activeIndex = frontCards.getIndex() - 1; //xxx
							controller.flippedToView(activeIndex);
						} else {
							swapCards();
							frontCards.setView(-1, null);
							if (-angle >= MAX_TIP_ANGLE)
								angle = -MAX_TIP_ANGLE;
							angle += 180;
						}
					}
					lastY = event.getY();
					return true;
				}

				return isOnTouchEvent;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				if (state == STATE_TOUCH) {
					delta = lastY - event.getY();
					rotateBy(180 * delta / texture.getContentHeight() * MOVEMENT_RATE);
					forward = angle >= 90;
					setState(STATE_AUTO_ROTATE);
				}				
				return isOnTouchEvent;
		}

		return false;
	}

	private void resetAxises() {
		frontCards.getTopCard().setAxis(Card.AXIS_TOP);
		frontCards.getBottomCard().setAxis(Card.AXIS_TOP);
		backCards.getBottomCard().setAxis(Card.AXIS_TOP);
		backCards.getTopCard().setAxis(Card.AXIS_BOTTOM);
	}
}